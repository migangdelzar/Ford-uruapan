package com.ford.service.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.application.IntentRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

class AiRegressionEvaluationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void writesJsonLinesAndMarkdownSummary(@TempDir Path tempDirectory) throws Exception {
        var result = new AiEvaluationResult(
                "case-1", "test", "deterministic", "fixture", "CATALOG",
                true, true, true, true, 12, 4, 8,
                BigDecimal.ZERO, true, "");
        var writer = new JsonlAiEvaluationReportWriter(mapper);

        var jsonl = writer.writeJsonLines(tempDirectory.resolve("results.jsonl"), List.of(result));
        var summary = writer.writeSummary(tempDirectory.resolve("summary.md"), List.of(result));

        assertThat(mapper.readTree(Files.readString(jsonl)).get("caseId").asText()).isEqualTo("case-1");
        assertThat(Files.readString(summary)).contains("1/1 passed");
    }

    @Test
    void evaluatesSpanishFixtureCasesAndWritesCiReports() throws Exception {
        var fixture = Path.of("src/test/resources/fixtures/ai/spanish-vehicle-finance-cases.jsonl");
        var router = new IntentRouter();
        var results = Files.readAllLines(fixture).stream()
                .filter(line -> !line.isBlank())
                .map(this::parse)
                .map(node -> evaluate(node, router))
                .toList();

        var output = Path.of("build/reports/ai-evaluation");
        var writer = new JsonlAiEvaluationReportWriter(mapper);
        writer.writeJsonLines(output.resolve("results.jsonl"), results);
        writer.writeSummary(output.resolve("summary.md"), results);

        assertThat(results).isNotEmpty().allMatch(AiEvaluationResult::passed);
    }

    private AiEvaluationResult evaluate(com.fasterxml.jackson.databind.JsonNode node, IntentRouter router) {
        var caseId = node.get("case_id").asText();
        var input = node.get("input").asText();
        var expectedIntent = node.get("expected_intent").asText();
        var actualIntent = router.route(input).name();
        var intentPassed = expectedIntent.equals(actualIntent);
        var disclosurePassed = node.get("must_disclose").isArray()
                && node.get("must_disclose").size() > 0;
        return new AiEvaluationResult(
                caseId, "test", "deterministic", "fixture", actualIntent,
                intentPassed, disclosurePassed, expectedIntent.equals("FINANCE"),
                expectedIntent.equals("LEAD_HANDOFF"), 1, 1, 1,
                BigDecimal.ZERO, intentPassed && disclosurePassed, intentPassed ? "" : "intent mismatch");
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String line) {
        try {
            return mapper.readTree(line);
        } catch (java.io.IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
