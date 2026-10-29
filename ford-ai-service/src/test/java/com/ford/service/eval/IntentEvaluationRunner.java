package com.ford.service.eval;

import com.ford.service.ai.adapter.out.rules.RulesConversationDecisionAdapter;
import com.ford.service.ai.application.ClassificationContext;
import com.ford.service.ai.application.ConversationDecisionPort;
import com.ford.service.ai.application.ConversationDecisionService;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.configuration.ClassificationProperties;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/** Runs the deterministic decision path with model classification disabled. */
public final class IntentEvaluationRunner {

    private static final UUID TENANT_ID = new UUID(0L, 1L);
    private static final UUID CONVERSATION_ID = new UUID(0L, 2L);

    private final ConversationDecisionService decisions;

    public IntentEvaluationRunner() {
        this(defaultDecisionService());
    }

    IntentEvaluationRunner(ConversationDecisionService decisions) {
        this.decisions = Objects.requireNonNull(decisions, "decisions are required");
    }

    public IntentEvaluationReport run(IntentEvaluationManifest manifest) {
        Objects.requireNonNull(manifest, "manifest is required");
        var results = manifest.cases().stream().map(this::evaluate).toList();
        return new IntentEvaluationReport(manifest.suiteId(), manifest.version(), results);
    }

    private IntentEvaluationCaseResult evaluate(IntentEvaluationCase evaluationCase) {
        var decision = decisions.decide(new ClassificationContext(TENANT_ID, CONVERSATION_ID,
                evaluationCase.message(),
                evaluationCase.memoryContext().isBlank() ? null : evaluationCase.memoryContext(),
                "BOT_ACTIVE", false));
        var failures = new ArrayList<String>();
        if (decision.intent() != evaluationCase.expectedIntent()) {
            failures.add("INTENT_MISMATCH");
        }
        if (decision.toolFamily() != evaluationCase.expectedToolFamily()) {
            failures.add("TOOL_FAMILY_MISMATCH");
        }
        if (evaluationCase.toolMode() == IntentEvaluationCase.ToolMode.REQUIRED
                && decision.toolFamily() == ToolFamily.NONE) {
            failures.add("TOOL_REQUIRED_MISSING");
        }
        if (evaluationCase.toolMode() == IntentEvaluationCase.ToolMode.FORBIDDEN
                && decision.toolFamily() != ToolFamily.NONE) {
            failures.add("FORBIDDEN_TOOL_SELECTED");
        }
        if (evaluationCase.forbiddenToolFamilies().contains(decision.toolFamily())) {
            failures.add("FORBIDDEN_TOOL_FAMILY_SELECTED");
        }
        return new IntentEvaluationCaseResult(evaluationCase.caseId(), evaluationCase.expectedIntent(),
                decision.intent(), evaluationCase.expectedToolFamily(), decision.toolFamily(),
                evaluationCase.toolMode(), failures.isEmpty(), failures);
    }

    private static ConversationDecisionService defaultDecisionService() {
        var properties = new ClassificationProperties();
        properties.setEnabled(false);
        properties.setMode("disabled");
        ConversationDecisionPort neverCalled = context -> {
            throw new IllegalStateException("model classifier must not run in offline evaluation");
        };
        return new ConversationDecisionService(neverCalled,
                new RulesConversationDecisionAdapter(new IntentRouter()), properties);
    }
}
