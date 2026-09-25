package com.ford.service.ai;

import com.ford.service.ai.api.AssistantTurn;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.application.Intent;
import com.ford.service.conversations.application.ConversationAssistantAdapter;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.api.ConversationTurnRequest;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationAssistantAdapterTest {

    @Test
    void mapsAiTurnIntoConversationReply() {
        var ai = mock(AssistantService.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        when(ai.respond(any(), any())).thenReturn(new AssistantTurn(
                "Te apoyo con la Ranger.", Intent.CATALOG, true, List.of(), "ollama", "llama3.2"));
        var adapter = new ConversationAssistantAdapter(ai);

        AssistantReply reply = adapter.respond(new ConversationTurnRequest(
                tenantId, conversationId, "Quiero comprar una Ranger", ConversationState.BOT_ACTIVE));

        assertThat(reply.text()).contains("Ranger");
        assertThat(reply.handoffRequired()).isTrue();
        verify(ai).respond(new com.ford.service.ai.application.AiExecutionContext(
                tenantId, false, conversationId), "Quiero comprar una Ranger");
    }

    @Test
    void requestsHumanHandoffWhenTheAiServiceIsUnavailable() {
        var adapter = new ConversationAssistantAdapter(() -> null);

        AssistantReply reply = adapter.respond(new ConversationTurnRequest(
                UUID.randomUUID(), UUID.randomUUID(), "Hola", ConversationState.BOT_ACTIVE));

        assertThat(reply.text()).isNotBlank();
        assertThat(reply.handoffRequired()).isTrue();
    }

    @Test
    void framesMemoryAsHistoricalContextWithTheCurrentMessageTakingPriority() {
        var ai = mock(AssistantService.class);
        when(ai.respond(any(), any())).thenReturn(new AssistantTurn(
                "Sí, puedo ayudarte.", Intent.GENERAL, false, List.of(), "openrouter", "openrouter/free"));
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var memoryKey = new ConversationMemoryKey(
                tenantId, com.ford.service.channels.domain.Channel.WHATSAPP,
                "phone-1", "customer-1", conversationId);
        var memory = new ConversationMemoryContext(memoryKey, "Busca una Ranger",
                List.of(new ConversationMessage(UUID.randomUUID(), conversationId,
                        ConversationMessage.Direction.INBOUND, "inbound-1", "Hola", Instant.now()),
                        new ConversationMessage(UUID.randomUUID(), conversationId,
                                ConversationMessage.Direction.OUTBOUND, "assistant:reply-1",
                                "¡Hola! ¿En qué te apoyo?", Instant.now())),
                List.of(), 1);

        new ConversationAssistantAdapter(ai).respond(new ConversationTurnRequest(
                tenantId, conversationId, "Quiero ver promociones", ConversationState.BOT_ACTIVE, memory));

        var context = org.mockito.ArgumentCaptor.forClass(com.ford.service.ai.application.AiExecutionContext.class);
        verify(ai).respond(context.capture(), org.mockito.ArgumentMatchers.eq("Quiero ver promociones"));
        assertThat(context.getValue().memoryContext())
                .contains("HISTORIAL DE CONVERSACIÓN")
                .contains("solo para continuidad")
                .contains("pregunta actual")
                .contains("asistente: ¡Hola! ¿En qué te apoyo?")
                .contains("FIN DEL HISTORIAL");
    }
}
