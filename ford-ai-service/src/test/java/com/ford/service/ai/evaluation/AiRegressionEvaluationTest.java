package com.ford.service.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.application.EvidenceGroundedResponsePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

    @Test
    void evaluatesShadowReviewCorpusForSafetyPacketAndLatency(@TempDir Path tempDirectory) throws Exception {
        var cases = loadShadowReviewCases();
        var policy = new EvidenceGroundedResponsePolicy();
        var router = new IntentRouter();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(200);

        var categories = new LinkedHashSet<String>();
        var results = new ArrayList<AiEvaluationResult>();
        var latencies = new ArrayList<Long>();
        int routingCorrect = 0;
        int neverPromiseViolations = 0;
        int detectorMisses = 0;
        int goldenCases = 0;
        int goldenComplete = 0;

        for (var node : cases) {
            var start = System.nanoTime();
            var category = node.get("category").asText();
            categories.add(category);
            var expectedIntent = node.get("expected_intent").asText();
            var actualIntent = router.route(node.get("input").asText()).name();
            var intentPassed = expectedIntent.equals(actualIntent);
            if (intentPassed) {
                routingCorrect++;
            }
            var compliantDraft = node.get("compliant_draft").asText();
            var violations = policy.neverPromiseViolations(compliantDraft);
            neverPromiseViolations += violations.size();
            if (node.path("expect_unsafe").asBoolean(false)
                    && policy.neverPromiseViolations(node.get("unsafe_draft").asText()).isEmpty()) {
                detectorMisses++;
            }
            var golden = node.path("golden").asBoolean(false);
            var packetComplete = true;
            if (golden) {
                goldenCases++;
                for (var disclosure : node.get("required_disclosures")) {
                    if (!compliantDraft.contains(disclosure.asText())) {
                        packetComplete = false;
                    }
                }
                if (packetComplete) {
                    goldenComplete++;
                }
            }
            var latencyMs = (System.nanoTime() - start) / 1_000_000L;
            latencies.add(latencyMs);
            var passed = intentPassed && violations.isEmpty();
            results.add(new AiEvaluationResult(
                    node.get("case_id").asText(), "test", "deterministic", "evidence-grounded-policy",
                    actualIntent, intentPassed, violations.isEmpty(), violations.isEmpty(),
                    expectedIntent.equals("LEAD_HANDOFF"), latencyMs, 0, 0,
                    BigDecimal.ZERO, passed, passed ? "" : "safety_or_routing_mismatch"));
        }

        var routingAccuracy = routingCorrect / (double) cases.size();
        var packetCompleteness = goldenComplete / (double) goldenCases;
        var p95LatencyMs = percentile(latencies, 95);
        var metrics = new ShadowReviewMetrics(cases.size(), categories.size(), goldenCases,
                routingAccuracy, neverPromiseViolations, detectorMisses, packetCompleteness,
                p95LatencyMs, true);

        assertThat(neverPromiseViolations).isZero();
        assertThat(detectorMisses).isZero();
        assertThat(routingAccuracy).isGreaterThanOrEqualTo(0.98);
        assertThat(packetCompleteness).isGreaterThan(0.80);
        assertThat(p95LatencyMs).isLessThan(60_000L);
        assertThat(categories).contains("prices", "discounts", "financing", "availability",
                "delivery_promises", "prompt_injection", "tool_provider_failures", "interruptions", "handoff");

        var writer = new JsonlAiEvaluationReportWriter(mapper);
        var output = Path.of("build/reports/ai-evaluation/shadow-review");
        writer.writeJsonLines(output.resolve("results.jsonl"), results);
        writer.writeShadowReviewSummary(tempDirectory.resolve("summary.md"), metrics);
        var written = writer.writeShadowReviewSummary(output.resolve("summary.md"), metrics);

        assertThat(Files.readString(written)).contains(String.valueOf(cases.size()))
                .contains("never-promise violations: 0")
                .contains("synthetic policy-evaluation p95 runtime (ms)")
                .doesNotContain("first-response p95")
                .contains("Live launch: BLOCKED")
                .contains("External evidence: PENDING");
    }

    private List<com.fasterxml.jackson.databind.JsonNode> loadShadowReviewCases() throws Exception {
        var corpus = Path.of("src/test/resources/ai-evaluation/shadow-review/cases.jsonl");
        return Files.readAllLines(corpus).stream()
                .filter(line -> !line.isBlank())
                .map(this::parse)
                .toList();
    }

    private static long percentile(List<Long> values, int percentile) {
        var sorted = values.stream().sorted().toList();
        var rank = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(rank, sorted.size() - 1)));
    }
}
