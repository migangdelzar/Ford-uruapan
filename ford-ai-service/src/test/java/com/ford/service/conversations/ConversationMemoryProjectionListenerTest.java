package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.ConversationMemoryProjectionListener;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryFact;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConversationMemoryProjectionListenerTest {

    @Test
    void delegatesTheVersionedProjectionUpdateToMemoryPort() {
        var memory = mock(ConversationMemoryPort.class);
        var listener = new ConversationMemoryProjectionListener(memory);
        var key = key();
        var update = new MemoryProjectionUpdate("Busca Ranger", List.of(new MemoryFact("interest", "Ranger", null)), null);
        var event = new ConversationMemoryProjectionRefreshRequested(key, update, 7);

        listener.on(event);

        verify(memory).updateProjection(key, update, 7);
    }

    @Test
    void propagatesProjectionFailureSoPublicationRemainsRetryable() {
        var memory = mock(ConversationMemoryPort.class);
        var listener = new ConversationMemoryProjectionListener(memory);
        var key = key();
        var update = new MemoryProjectionUpdate("Busca Ranger", List.of(), null);
        var event = new ConversationMemoryProjectionRefreshRequested(key, update, 0);
        doThrow(new IllegalStateException("projection unavailable"))
                .when(memory).updateProjection(key, update, 0);

        assertThatThrownBy(() -> listener.on(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projection unavailable");
    }

    private static ConversationMemoryKey key() {
        return new ConversationMemoryKey(UUID.randomUUID(), Channel.WHATSAPP,
                "phone-1", "customer-1", UUID.randomUUID());
    }
}
