package com.ford.service.eval;

import com.ford.service.ai.application.ConversationIntent;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Deterministic evaluation totals, grouped by expected intent. */
public record IntentEvaluationReport(
        String suiteId,
        String manifestVersion,
        List<IntentEvaluationCaseResult> cases) {

    public IntentEvaluationReport {
        suiteId = Objects.requireNonNull(suiteId, "suiteId is required");
        manifestVersion = Objects.requireNonNull(manifestVersion, "manifestVersion is required");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases are required"));
    }

    public int totalCases() {
        return cases.size();
    }

    public int passedCases() {
        return (int) cases.stream().filter(IntentEvaluationCaseResult::passed).count();
    }

    public int failedCases() {
        return totalCases() - passedCases();
    }

    public List<IntentSummary> byIntent() {
        return Arrays.stream(ConversationIntent.values())
                .map(intent -> {
                    var selected = cases.stream()
                            .filter(result -> result.expectedIntent() == intent)
                            .toList();
                    return new IntentSummary(intent.name(), selected.size(),
                            (int) selected.stream().filter(IntentEvaluationCaseResult::passed).count());
                })
                .filter(summary -> summary.total() > 0)
                .toList();
    }

    public record IntentSummary(String intent, int total, int passed) {
        public IntentSummary {
            Objects.requireNonNull(intent, "intent is required");
        }
    }
}
