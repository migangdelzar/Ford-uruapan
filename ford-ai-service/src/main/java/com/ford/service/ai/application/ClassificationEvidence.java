package com.ford.service.ai.application;

import java.util.Objects;

public record ClassificationEvidence(
        ClassifierType classifier,
        String classification,
        double confidence,
        String reasonCode,
        String provider,
        String model,
        long latencyMs,
        boolean fallbackUsed) {

    public ClassificationEvidence {
        Objects.requireNonNull(classifier, "classifier is required");
        if (classification == null || classification.isBlank()) {
            throw new IllegalArgumentException("classification is required");
        }
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reason code is required");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latency must not be negative");
        }
    }
}
