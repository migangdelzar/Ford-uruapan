package com.ford.service.ai.application;

import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.api.AssistantTurn;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SpringAiAssistantService implements AssistantService {

    private static final String FALLBACK_TEXT = "En este momento te comunicaré con un asesor humano.";

    private final AssistantOrchestrator orchestrator;
    private final IntentRouter intentRouter;
    private final ModelProviderPolicy providerPolicy;

    public SpringAiAssistantService(AssistantOrchestrator orchestrator,
                                    ModelProviderPolicy providerPolicy) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.intentRouter = new IntentRouter();
        this.providerPolicy = Objects.requireNonNull(providerPolicy);
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
                    context.containsSensitiveData()));
            return new AssistantTurn(response.content(), intent, response.handoffRequired(),
                    response.sourceDocumentIds(), response.provider(), modelVersion(response.provider()));
        } catch (RuntimeException exception) {
            return new AssistantTurn(FALLBACK_TEXT, intent, true, List.of(), "fallback", "none");
        }
    }

    private String modelVersion(String provider) {
        return providerPolicy.providers().stream()
                .filter(candidate -> candidate.id().equals(provider))
                .map(ProviderDescriptor::modelVersion)
                .findFirst()
                .orElse("unknown");
    }
}
