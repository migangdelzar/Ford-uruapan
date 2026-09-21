package com.ford.service.conversations.application;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.api.ConversationTurnRequest;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMessage;

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
                request.tenantId(), false, request.conversationId(), memoryPrompt(request.memory())), request.message());
        return new AssistantReply(turn.text(), turn.requestsHuman());
    }

    private static String memoryPrompt(ConversationMemoryContext memory) {
        if (memory == null) {
            return null;
        }
        var prompt = new StringBuilder("Resumen: ").append(safe(memory.summary()));
        if (!memory.recentMessages().isEmpty()) {
            prompt.append("\nHistorial reciente:");
            for (var message : memory.recentMessages()) {
                prompt.append("\n- ").append(message.direction() == ConversationMessage.Direction.INBOUND
                        ? "usuario" : "asistente").append(": ").append(safe(message.content()));
            }
        }
        if (!memory.durableFacts().isEmpty()) {
            prompt.append("\nHechos relevantes:");
            memory.durableFacts().forEach(fact -> prompt.append("\n- ")
                    .append(safe(fact.kind())).append(": ").append(safe(fact.content())));
        }
        return prompt.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("[MEMORIA DE SESIÓN]", "[memoria]")
                .replace("[/MEMORIA DE SESIÓN]", "[/memoria]");
    }
}
