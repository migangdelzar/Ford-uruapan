package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.ConversationMemoryProjectionListener;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryFact;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.observability.AiObservationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMemoryProjectionListenerTest {

    @Test
    void delegatesTheVersionedProjectionUpdateToMemoryPort() {
        var memory = mock(ConversationMemoryPort.class);
        var observations = mock(AiObservationService.class);
        var listener = new ConversationMemoryProjectionListener(memory, observations);
        var key = key();
        var update = new MemoryProjectionUpdate("Busca Ranger", List.of(new MemoryFact("interest", "Ranger", null)), null);
        var event = new ConversationMemoryProjectionRefreshRequested(key, update, 7);
        when(memory.updateProjection(key, update, 7)).thenReturn(new com.ford.service.conversations.domain.MemoryUpdateResult(true, 8));

        listener.on(event);

        verify(memory).updateProjection(key, update, 7);
        verify(observations).event("ford.conversation.memory_projection", Map.of("outcome", "success"));
    }

    @Test
    void propagatesProjectionFailureSoPublicationRemainsRetryable() {
        var memory = mock(ConversationMemoryPort.class);
        var observations = mock(AiObservationService.class);
        var listener = new ConversationMemoryProjectionListener(memory, observations);
        var key = key();
        var update = new MemoryProjectionUpdate("Busca Ranger", List.of(), null);
        var event = new ConversationMemoryProjectionRefreshRequested(key, update, 0);
        doThrow(new IllegalStateException("projection unavailable"))
                .when(memory).updateProjection(key, update, 0);

        assertThatThrownBy(() -> listener.on(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projection unavailable");
        verify(observations).event("ford.conversation.memory_projection",
                Map.of("outcome", "failure", "failure_category", "persistence"));
    }

    private static ConversationMemoryKey key() {
        return new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "phone-1", "customer-1", UUID.randomUUID());
    }
}
