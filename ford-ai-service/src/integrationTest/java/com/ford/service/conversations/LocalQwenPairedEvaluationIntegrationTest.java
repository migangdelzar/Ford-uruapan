package com.ford.service.conversations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Compares two completed local runs; each run must have used its own snapshot/JVM. */
@EnabledIfEnvironmentVariable(named = "FORD_QWEN_PAIR_COMPARE_ENABLED", matches = "true")
class LocalQwenPairedEvaluationIntegrationTest {
    @Test
    void comparesBaselineAndCandidateReportsFromSeparateRuns() throws Exception {
        var baselinePath = Path.of(System.getenv().getOrDefault("FORD_QWEN_BASELINE_REPORT",
                "build/reports/local-qwen/evaluation-report-oct22-baseline.json"));
        var candidatePath = Path.of(System.getenv().getOrDefault("FORD_QWEN_CANDIDATE_REPORT",
                "build/reports/local-qwen/evaluation-report-oct25-candidate.json"));
        var cases = CatalogMemoryEvaluationScenario.load(new tools.jackson.databind.ObjectMapper());
        var paired = LocalQwenEvaluationReportWriter.compare(baselinePath, candidatePath, cases);
        var output = LocalQwenEvaluationReportWriter.writePaired(Path.of("build/reports"),
                "paired-evaluation-" + UUID.randomUUID().toString().replace("-", "") + ".json", paired);

        assertThat(paired.comparison().passed())
                .as("paired acceptance; report: %s; regressions=%s unfixedGaps=%s criticalFailures=%s missing=%s",
                        output, paired.comparison().regressions(), paired.comparison().unfixedGaps(),
                        paired.comparison().criticalFailures(), paired.comparison().missingCaseIds())
                .isTrue();
    }
}
