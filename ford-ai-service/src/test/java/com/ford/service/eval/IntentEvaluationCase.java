package com.ford.service.eval;

import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.ToolFamily;

import java.util.List;
import java.util.Objects;

/** One synthetic routing case with an explicit, reviewable expectation. */
public record IntentEvaluationCase(
        String caseId,
        GeneralSubtype generalSubtype,
        String message,
        String memoryContext,
        ConversationIntent expectedIntent,
        ToolFamily expectedToolFamily,
        ToolMode toolMode,
        List<ToolFamily> forbiddenToolFamilies,
        ExpectedBehavior expectedBehavior,
        String notes) {

    public enum GeneralSubtype { COURTESY, DISCOVERY, AMBIGUOUS, MIXED_REQUEST, NO_TOOL_NEEDED }

    public enum ToolMode { REQUIRED, FORBIDDEN }

    public enum ExpectedBehavior { ANSWER, CLARIFY, ACKNOWLEDGE }

    public IntentEvaluationCase {
        caseId = Objects.requireNonNull(caseId, "caseId is required");
        message = Objects.requireNonNull(message, "message is required");
        memoryContext = memoryContext == null ? "" : memoryContext;
        expectedIntent = Objects.requireNonNull(expectedIntent, "expectedIntent is required");
        expectedToolFamily = Objects.requireNonNull(expectedToolFamily, "expectedToolFamily is required");
        toolMode = Objects.requireNonNull(toolMode, "toolMode is required");
        forbiddenToolFamilies = List.copyOf(
                forbiddenToolFamilies == null ? List.of() : forbiddenToolFamilies);
        expectedBehavior = Objects.requireNonNull(expectedBehavior, "expectedBehavior is required");
        notes = notes == null ? "" : notes;
    }
}
