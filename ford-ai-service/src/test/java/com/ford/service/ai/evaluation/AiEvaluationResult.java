package com.ford.service.ai.evaluation;

import java.math.BigDecimal;

record AiEvaluationResult(
        String caseId,
        String applicationVersion,
        String provider,
        String model,
        String intent,
        boolean toolExpectationPassed,
        boolean groundednessPassed,
        boolean financeSafetyPassed,
        boolean leadScorePassed,
        long latencyMs,
        int inputTokens,
        int outputTokens,
        BigDecimal estimatedCostMxn,
        boolean passed,
        String failureReason) {
}
