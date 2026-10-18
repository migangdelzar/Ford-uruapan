package com.ford.service.ai.evaluation;

/**
 * Machine-generated metrics for the shadow-review evaluation.
 * All inputs are synthetic; no customer data is used.
 */
record ShadowReviewMetrics(
        int totalCases,
        int categories,
        int goldenCases,
        double routingAccuracy,
        int neverPromiseViolations,
        int detectorMisses,
        double packetCompleteness,
        long p95LatencyMs,
        boolean passed) {
}
