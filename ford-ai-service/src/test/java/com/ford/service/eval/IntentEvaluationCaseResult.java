package com.ford.service.eval;

import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.ToolFamily;

import java.util.List;
import java.util.Objects;

/** One evaluated case with stable failure reason codes only. */
public record IntentEvaluationCaseResult(
        String caseId,
        ConversationIntent expectedIntent,
        ConversationIntent actualIntent,
        ToolFamily expectedToolFamily,
        ToolFamily actualToolFamily,
        IntentEvaluationCase.ToolMode toolMode,
        boolean passed,
        List<String> failureCodes) {

    public IntentEvaluationCaseResult {
        caseId = Objects.requireNonNull(caseId, "caseId is required");
        expectedIntent = Objects.requireNonNull(expectedIntent, "expectedIntent is required");
        actualIntent = Objects.requireNonNull(actualIntent, "actualIntent is required");
        expectedToolFamily = Objects.requireNonNull(expectedToolFamily, "expectedToolFamily is required");
        actualToolFamily = Objects.requireNonNull(actualToolFamily, "actualToolFamily is required");
        toolMode = Objects.requireNonNull(toolMode, "toolMode is required");
        failureCodes = List.copyOf(failureCodes == null ? List.of() : failureCodes);
    }
}
