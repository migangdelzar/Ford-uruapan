package com.ford.service.eval;

import java.util.List;
import java.util.Objects;

/** One versioned evaluation suite. */
public record IntentEvaluationManifest(
        String suiteId,
        String version,
        List<IntentEvaluationCase> cases) {

    public IntentEvaluationManifest {
        suiteId = Objects.requireNonNull(suiteId, "suiteId is required");
        version = Objects.requireNonNull(version, "version is required");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases are required"));
    }
}
