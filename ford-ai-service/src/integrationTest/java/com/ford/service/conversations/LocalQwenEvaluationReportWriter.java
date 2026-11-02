package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Persists content-free per-case outcomes and a reproducible run report. */
final class LocalQwenEvaluationReportWriter {
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9-]{1,80}");

    private final String snapshotLabel;
    private final String snapshotSha256;
    private final String modelConfigId;
    private final boolean nameIndexEnabled;
    private final Path jsonlPath;
    private final Path reportPath;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CatalogMemoryEvaluationReport.CaseResult> results = new ArrayList<>();
    private boolean completed;

    LocalQwenEvaluationReportWriter(Path reportsDirectory, String runId,
                                    LocalCatalogSnapshotSelection.Label snapshotLabel,
                                    String snapshotSha256, String modelConfigId,
                                    boolean nameIndexEnabled) throws IOException {
        Objects.requireNonNull(reportsDirectory, "reports directory is required");
        if (runId == null || !SAFE_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("run ID must contain only letters, digits, and hyphens");
        }
        this.snapshotLabel = Objects.requireNonNull(snapshotLabel, "snapshot label is required").wireName();
        if (snapshotSha256 == null || !snapshotSha256.matches("(?i)[a-f0-9]{64}")) {
            throw new IllegalArgumentException("snapshot manifest SHA-256 is invalid");
        }
        this.snapshotSha256 = snapshotSha256;
        if (modelConfigId == null || !modelConfigId.matches("[A-Za-z0-9][A-Za-z0-9._:_-]{0,99}")) {
            throw new IllegalArgumentException("model configuration ID is invalid");
        }
        this.modelConfigId = modelConfigId;
        this.nameIndexEnabled = nameIndexEnabled;
        var root = reportsDirectory.toAbsolutePath().normalize();
        var directory = root.resolve("local-qwen").normalize();
        if (!directory.startsWith(root)) throw new IllegalArgumentException("report directory escaped reports root");
        Files.createDirectories(directory);
        jsonlPath = directory.resolve("evaluation-cases-" + runId + ".jsonl").normalize();
        reportPath = directory.resolve("evaluation-report-" + runId + ".json").normalize();
        if (!jsonlPath.startsWith(directory) || !reportPath.startsWith(directory)) {
            throw new IllegalArgumentException("evaluation report path escaped local report directory");
        }
        Files.writeString(jsonlPath, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    Path jsonlPath() {
        return jsonlPath;
    }

    Path reportPath() {
        return reportPath;
    }

    void append(CatalogMemoryEvaluationReport.CaseResult result) throws IOException {
        Objects.requireNonNull(result, "case result is required");
        if (completed) throw new IllegalStateException("evaluation report is already complete");
        if (results.stream().anyMatch(existing -> existing.caseId().equals(result.caseId()))) {
            throw new IllegalArgumentException("duplicate evaluation result case ID");
        }
        var row = new CaseRow(snapshotLabel, snapshotSha256, modelConfigId, nameIndexEnabled, result);
        Files.writeString(jsonlPath, objectMapper.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        results.add(result);
    }

    void complete() throws IOException {
        if (completed) throw new IllegalStateException("evaluation report is already complete");
        if (results.isEmpty()) throw new IllegalStateException("evaluation report needs at least one case result");
        var report = new RunReport(snapshotLabel, snapshotSha256, modelConfigId,
                nameIndexEnabled, List.copyOf(results));
        Files.writeString(reportPath, objectMapper.writeValueAsString(report) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        completed = true;
    }

    static RunReport read(Path reportPath) throws IOException {
        Objects.requireNonNull(reportPath, "report path is required");
        var report = new ObjectMapper().readValue(Files.readString(reportPath, StandardCharsets.UTF_8), RunReport.class);
        validate(report);
        return report;
    }

    static PairedRunReport compare(Path baselinePath, Path candidatePath,
                                   List<CatalogMemoryEvaluationScenario.Case> reviewedCases) throws IOException {
        var baseline = read(baselinePath);
        var candidate = read(candidatePath);
        if (!"oct22-baseline".equals(baseline.snapshotLabel())) {
            throw new IllegalArgumentException("first run report must be oct22-baseline");
        }
        if (!"oct25-candidate".equals(candidate.snapshotLabel())) {
            throw new IllegalArgumentException("second run report must be oct25-candidate");
        }
        if (baseline.snapshotSha256().equalsIgnoreCase(candidate.snapshotSha256())) {
            throw new IllegalArgumentException("baseline and candidate snapshot hashes must differ");
        }
        if (!baseline.modelConfigId().equals(candidate.modelConfigId())) {
            throw new IllegalArgumentException("paired runs must use the same model configuration");
        }
        if (baseline.nameIndexEnabled() != candidate.nameIndexEnabled()) {
            throw new IllegalArgumentException("paired runs must use the same name-index setting");
        }
        var paired = CatalogMemoryEvaluationReport.compare(reviewedCases,
                baseline.caseResults(), candidate.caseResults());
        return new PairedRunReport(baseline.snapshotLabel(), baseline.snapshotSha256(),
                candidate.snapshotLabel(), candidate.snapshotSha256(), baseline.modelConfigId(),
                baseline.nameIndexEnabled(), paired);
    }

    static Path writePaired(Path reportsDirectory, String fileName, PairedRunReport report) throws IOException {
        Objects.requireNonNull(reportsDirectory, "reports directory is required");
        Objects.requireNonNull(report, "paired report is required");
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.json")) {
            throw new IllegalArgumentException("paired report filename is invalid");
        }
        var root = reportsDirectory.toAbsolutePath().normalize();
        var directory = root.resolve("local-qwen").normalize();
        if (!directory.startsWith(root)) throw new IllegalArgumentException("paired report directory escaped reports root");
        Files.createDirectories(directory);
        var path = directory.resolve(fileName).normalize();
        if (!path.startsWith(directory)) throw new IllegalArgumentException("paired report path escaped local report directory");
        Files.writeString(path, new ObjectMapper().writeValueAsString(report) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return path;
    }

    private static void validate(RunReport report) {
        if (report == null || report.caseResults() == null || report.caseResults().isEmpty()) {
            throw new IllegalArgumentException("evaluation run report is empty");
        }
        if (!List.of("oct22-baseline", "oct25-candidate").contains(report.snapshotLabel())) {
            throw new IllegalArgumentException("evaluation run snapshot label is invalid");
        }
        if (report.snapshotSha256() == null || !report.snapshotSha256().matches("(?i)[a-f0-9]{64}")) {
            throw new IllegalArgumentException("evaluation run snapshot hash is invalid");
        }
        if (report.modelConfigId() == null || report.modelConfigId().isBlank()) {
            throw new IllegalArgumentException("evaluation run model configuration is missing");
        }
        var ids = new HashSet<String>();
        for (var result : report.caseResults()) {
            if (result == null || !ids.add(result.caseId())) {
                throw new IllegalArgumentException("evaluation run contains duplicate or invalid case results");
            }
        }
    }

    record CaseRow(String snapshotLabel, String snapshotSha256, String modelConfigId, boolean nameIndexEnabled,
                   CatalogMemoryEvaluationReport.CaseResult caseResult) { }

    record RunReport(String snapshotLabel, String snapshotSha256, String modelConfigId, boolean nameIndexEnabled,
                     List<CatalogMemoryEvaluationReport.CaseResult> caseResults) { }

    record PairedRunReport(String baselineLabel, String baselineSha256, String candidateLabel,
                           String candidateSha256, String modelConfigId, boolean nameIndexEnabled,
                           CatalogMemoryEvaluationReport.PairedResult comparison) { }
}
