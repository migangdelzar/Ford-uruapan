package com.ford.service.conversations.application;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.api.ConversationTurnRequest;
import com.ford.service.conversations.application.port.out.ConversationAssistant;

import java.util.Objects;
import java.util.function.Supplier;

public final class ConversationAssistantAdapter implements ConversationAssistant {

    private static final String FALLBACK_TEXT = "En este momento te comunicaré con un asesor humano.";

    private final Supplier<AssistantService> assistantProvider;

    public ConversationAssistantAdapter(AssistantService assistant) {
        this(() -> Objects.requireNonNull(assistant));
    }

    public ConversationAssistantAdapter(Supplier<AssistantService> assistantProvider) {
        this.assistantProvider = Objects.requireNonNull(assistantProvider);
    }

    @Override
    public AssistantReply respond(ConversationTurnRequest request) {
        var assistant = assistantProvider.get();
        if (assistant == null) {
            return new AssistantReply(FALLBACK_TEXT, true);
        }
        var turn = assistant.respond(new AiExecutionContext(
                request.tenantId(), false, request.conversationId()), request.message());
        return new AssistantReply(turn.text(), turn.requestsHuman());
    }
}
