package com.ford.service.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRoutingEvaluationTest {

    private static final Path MANIFEST = Path.of("src/test/resources/eval/intent-routing-v1.json");
    private static final Path BASELINE = Path.of("src/test/resources/eval/intent-routing-v1-baseline.json");

    @Test
    void everyManifestCasePassesAndWritesLocalReports() throws Exception {
        var manifest = new IntentEvaluationManifestLoader().load(MANIFEST);
        var report = new IntentEvaluationRunner().run(manifest);
        var paths = new IntentEvaluationReportWriter(Path.of("build/reports"))
                .write(report);

        assertThat(report.failedCases())
                .as("failed cases: " + report.cases().stream()
                        .filter(result -> !result.passed())
                        .map(result -> result.caseId() + "=" + result.failureCodes())
                        .toList())
                .isZero();
        assertThat(report.passedCases()).isEqualTo(manifest.cases().size());
        assertThat(paths.json()).exists();
        assertThat(paths.markdown()).exists();
    }

    @Test
    void baselineCasesAreNeverSilentlyDeleted() throws Exception {
        var manifest = new IntentEvaluationManifestLoader().load(MANIFEST);
        var baseline = new ObjectMapper().readTree(Files.readString(BASELINE));
        var baselineIds = new ArrayList<String>();
        baseline.get("caseIds").forEach(node -> baselineIds.add(node.asText()));

        assertThat(manifest.cases()).extracting(IntentEvaluationCase::caseId)
                .containsAll(baselineIds);
    }
}
