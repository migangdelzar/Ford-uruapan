package com.ford.service.ai.application;

import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.api.AssistantTurn;
import com.ford.service.shared.HumanHandoffPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SpringAiAssistantService implements AssistantService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAssistantService.class);

    private final AssistantOrchestrator orchestrator;
    private final IntentRouter intentRouter;
    private final HumanHandoffPolicy handoffPolicy;
    private final ModelProviderPolicy providerPolicy;
    private final boolean logProviderErrors;

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    ModelProviderPolicy providerPolicy) {
        this(orchestrator, new HumanHandoffPolicy(true), providerPolicy, false);
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy) {
        this(orchestrator, handoffPolicy, providerPolicy, false);
    }

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    HumanHandoffPolicy handoffPolicy,
                                    ModelProviderPolicy providerPolicy,
                                    boolean logProviderErrors) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.intentRouter = new IntentRouter();
        this.handoffPolicy = Objects.requireNonNull(handoffPolicy);
        this.providerPolicy = Objects.requireNonNull(providerPolicy);
        this.logProviderErrors = logProviderErrors;
    }

    @Override
    public AssistantTurn respond(AiExecutionContext context, String userText) {
        Objects.requireNonNull(context);
        if (context.tenantId() == null || userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("tenant and user text are required");
        }

        var intent = intentRouter.route(userText);
        var conversationId = context.conversationId() == null ? UUID.randomUUID() : context.conversationId();
        try {
            AssistantResponse response = orchestrator.respond(new AssistantRequest(
                    context.tenantId(), conversationId, userText, "BOT_ACTIVE",
                    context.memoryContext(),
                    context.containsSensitiveData()));
            return new AssistantTurn(response.content(), intent, response.handoffRequired(),
                    response.sourceDocumentIds(), response.provider(), modelVersion(response.provider()));
        } catch (RuntimeException exception) {
            log.warn("AI completion failed: providers={} exceptionType={} {}",
                    providerSummary(), exception.getClass().getSimpleName(),
                    AiProviderFailureDiagnostics.describe(exception, logProviderErrors));
            return new AssistantTurn(handoffPolicy.fallbackText(), intent,
                    handoffPolicy.enabled(), List.of(), "fallback", "none");
        }
    }

    private String providerSummary() {
        return providerPolicy.providers().stream()
                .map(provider -> provider.id() + ":" + provider.modelVersion())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
    }

    private String modelVersion(String provider) {
        return providerPolicy.providers().stream()
                .filter(candidate -> candidate.id().equals(provider))
                .map(ProviderDescriptor::modelVersion)
                .findFirst()
                .orElse("unknown");
    }
}
