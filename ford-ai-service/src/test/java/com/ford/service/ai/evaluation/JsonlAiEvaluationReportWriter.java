package com.ford.service.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class JsonlAiEvaluationReportWriter {

    private final ObjectMapper mapper;

    JsonlAiEvaluationReportWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Path writeJsonLines(Path output, List<AiEvaluationResult> results) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        var content = results.stream()
                .map(this::json)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
        Files.writeString(output, content + (content.isEmpty() ? "" : System.lineSeparator()));
        return output;
    }

    Path writeSummary(Path output, List<AiEvaluationResult> results) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        var passed = results.stream().filter(AiEvaluationResult::passed).count();
        var summary = "# AI evaluation\n\n"
                + passed + "/" + results.size() + " passed\n";
        Files.writeString(output, summary);
        return output;
    }

    private String json(AiEvaluationResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize AI evaluation result", exception);
        }
    }

    Path writeShadowReviewSummary(Path output, ShadowReviewMetrics metrics) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        var summary = "# Shadow Review Evaluation\n\n"
                + "- total cases: " + metrics.totalCases() + "\n"
                + "- categories covered: " + metrics.categories() + "\n"
                + "- golden cases: " + metrics.goldenCases() + "\n"
                + "- routing accuracy: " + String.format("%.3f", metrics.routingAccuracy()) + "\n"
                + "- never-promise violations: " + metrics.neverPromiseViolations() + "\n"
                + "- never-promise detector misses: " + metrics.detectorMisses() + "\n"
                + "- packet completeness (golden): " + String.format("%.3f", metrics.packetCompleteness()) + "\n"
                + "- synthetic policy-evaluation p95 runtime (ms): " + metrics.p95LatencyMs() + "\n"
                + "- synthetic-only: yes\n"
                + "- Live launch: BLOCKED\n"
                + "- External evidence: PENDING\n";
        Files.writeString(output, summary);
        return output;
    }
}
