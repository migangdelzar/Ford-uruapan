package com.ford.service.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Emits only case IDs, enum routes, pass/fail state, and stable reason codes. */
public final class IntentEvaluationReportWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_SUITE = Pattern.compile("[a-z0-9][a-z0-9-]{2,79}");
    private static final Pattern SAFE_VERSION = Pattern.compile("v[0-9]{1,3}");

    private final Path reportsDirectory;

    public IntentEvaluationReportWriter(Path reportsRoot) {
        Objects.requireNonNull(reportsRoot, "reports root is required");
        this.reportsDirectory = reportsRoot.toAbsolutePath().normalize().resolve("eval");
    }

    public ReportPaths write(IntentEvaluationReport report) throws IOException {
        Objects.requireNonNull(report, "report is required");
        if (!SAFE_SUITE.matcher(report.suiteId()).matches()
                || !SAFE_VERSION.matcher(report.manifestVersion()).matches()) {
            throw new IllegalArgumentException("report suite ID or version is not safe for a filename");
        }
        Files.createDirectories(reportsDirectory);
        var base = report.suiteId() + "-" + report.manifestVersion();
        var json = reportsDirectory.resolve(base + ".json");
        var markdown = reportsDirectory.resolve(base + ".md");
        Files.writeString(json, MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(json(report)) + "\n", StandardCharsets.UTF_8);
        Files.writeString(markdown, markdown(report), StandardCharsets.UTF_8);
        return new ReportPaths(json, markdown);
    }

    private static Map<String, Object> json(IntentEvaluationReport report) {
        var root = new LinkedHashMap<String, Object>();
        root.put("suiteId", report.suiteId());
        root.put("version", report.manifestVersion());
        root.put("totalCases", report.totalCases());
        root.put("passedCases", report.passedCases());
        root.put("failedCases", report.failedCases());
        var intents = new ArrayList<Map<String, Object>>();
        for (var summary : report.byIntent()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("intent", summary.intent());
            item.put("total", summary.total());
            item.put("passed", summary.passed());
            intents.add(item);
        }
        root.put("intents", intents);
        var cases = new ArrayList<Map<String, Object>>();
        for (var result : report.cases()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("caseId", result.caseId());
            item.put("expectedIntent", result.expectedIntent().name());
            item.put("actualIntent", result.actualIntent().name());
            item.put("expectedToolFamily", result.expectedToolFamily().name());
            item.put("actualToolFamily", result.actualToolFamily().name());
            item.put("toolMode", result.toolMode().name());
            item.put("passed", result.passed());
            item.put("failureCodes", result.failureCodes());
            cases.add(item);
        }
        root.put("cases", cases);
        return root;
    }

    private static String markdown(IntentEvaluationReport report) {
        var markdown = new StringBuilder(4096);
        markdown.append("# Intent Evaluation Report\n\n")
                .append("- Suite: `").append(report.suiteId()).append("`\n")
                .append("- Version: `").append(report.manifestVersion()).append("`\n")
                .append("- Total: ").append(report.totalCases()).append("\n")
                .append("- Passed: ").append(report.passedCases()).append("\n")
                .append("- Failed: ").append(report.failedCases()).append("\n\n")
                .append("## By intent\n\n")
                .append("| Intent | Passed | Total |\n")
                .append("| --- | --- | --- |\n");
        for (var summary : report.byIntent()) {
            markdown.append("| ").append(summary.intent()).append(" | ")
                    .append(summary.passed()).append(" | ").append(summary.total()).append(" |\n");
        }
        markdown.append("\n## Cases\n\n")
                .append("| Case | Intent | Tool family | Mode | Result | Failures |\n")
                .append("| --- | --- | --- | --- | --- | --- |\n");
        for (var result : report.cases()) {
            markdown.append("| ").append(result.caseId()).append(" | ")
                    .append(result.expectedIntent().name()).append(" | ")
                    .append(result.expectedToolFamily().name()).append(" | ")
                    .append(result.toolMode().name()).append(" | ")
                    .append(result.passed() ? "PASS" : "FAIL").append(" | ")
                    .append(String.join(", ", result.failureCodes())).append(" |\n");
        }
        return markdown.toString();
    }

    public record ReportPaths(Path json, Path markdown) {
        public ReportPaths {
            Objects.requireNonNull(json, "JSON path is required");
            Objects.requireNonNull(markdown, "Markdown path is required");
        }
    }
}
