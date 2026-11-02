package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LocalQwenEvaluationReportWriterTest {
    @TempDir Path tempDir;

    @Test
    void writesIncrementalSafeCaseResultsAndReadsTheCompletedRun() throws Exception {
        var writer = new LocalQwenEvaluationReportWriter(tempDir, "run-base",
                LocalCatalogSnapshotSelection.Label.OCT22_BASELINE, "a".repeat(64), "qwen3.5:4b_ctx8192", true);
        writer.append(result("ranger-discovery-2027", true));
        writer.complete();

        var report = LocalQwenEvaluationReportWriter.read(writer.reportPath());
        var json = Files.readString(writer.reportPath());
        assertThat(report.snapshotLabel()).isEqualTo("oct22-baseline");
        assertThat(report.snapshotSha256()).isEqualTo("a".repeat(64));
        assertThat(report.nameIndexEnabled()).isTrue();
        assertThat(report.caseResults()).extracting(CatalogMemoryEvaluationReport.CaseResult::caseId)
                .containsExactly("ranger-discovery-2027");
        assertThat(json).doesNotContain("SYNTHETIC_ANSWER_SENTINEL", "memory content", "contact@example.invalid");
    }

    @Test
    void rejectsPairedReportsWithWrongLabelsDifferentModelsOrSameSnapshotHash() throws Exception {
        var baselineWriter = new LocalQwenEvaluationReportWriter(tempDir, "run-baseline",
                LocalCatalogSnapshotSelection.Label.OCT22_BASELINE, "a".repeat(64), "qwen3.5:4b_ctx8192", false);
        var candidateWriter = new LocalQwenEvaluationReportWriter(tempDir, "run-candidate",
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, "b".repeat(64), "qwen3.5:4b_ctx8192", true);
        baselineWriter.append(result("ranger-discovery-2027", true));
        candidateWriter.append(result("ranger-discovery-2027", true));
        baselineWriter.complete();
        candidateWriter.complete();
        var cases = CatalogMemoryEvaluationScenario.load(new tools.jackson.databind.ObjectMapper());

        assertThatIllegalArgumentException().isThrownBy(() -> LocalQwenEvaluationReportWriter.compare(
                baselineWriter.reportPath(), candidateWriter.reportPath(), cases))
                .withMessageContaining("paired runs must use the same name-index setting");
    }

    @Test
    void writesPairedOutcomeWithoutTranscriptContent() throws Exception {
        var paired = new LocalQwenEvaluationReportWriter.PairedRunReport("oct22-baseline", "a".repeat(64),
                "oct25-candidate", "b".repeat(64), "qwen3.5:4b_ctx8192", true,
                new CatalogMemoryEvaluationReport.PairedResult(true, List.of(), List.of(), List.of(), List.of()));

        var output = LocalQwenEvaluationReportWriter.writePaired(tempDir, "paired-safe.json", paired);

        assertThat(Files.readString(output)).contains("oct22-baseline", "oct25-candidate", "\"passed\":true")
                .contains("\"nameIndexEnabled\":true")
                .doesNotContain("SYNTHETIC_ANSWER_SENTINEL", "prompt", "contact@example.invalid");
    }

    private static CatalogMemoryEvaluationReport.CaseResult result(String id, boolean correct) {
        return new CatalogMemoryEvaluationReport.CaseResult(id,
                CatalogMemoryEvaluationScenario.Category.DISCOVERY_IDENTITY,
                CatalogMemoryEvaluationScenario.BaselineStatus.EXPECTED_CORRECT,
                CatalogMemoryEvaluationScenario.CandidateIntent.REGRESSION_CONTROL,
                CatalogMemoryEvaluationScenario.Criticality.HIGH, correct, false,
                correct ? List.of() : List.of("REQUIRED_CLAIM_MISSING"));
    }
}
