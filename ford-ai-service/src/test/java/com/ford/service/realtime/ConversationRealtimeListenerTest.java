package com.ford.service.realtime;

import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.api.ConversationMemoryRestarted;
import com.ford.service.realtime.application.ConversationRealtimeListener;
import com.ford.service.realtime.application.RealtimeEventHub;
import com.ford.service.realtime.domain.FrontendEvent;
import com.ford.service.shared.ConversationTurnCompleted;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConversationRealtimeListenerTest {

    @Test
    void publishesMetadataOnlyUpdateAfterBotTurnCompletes() {
        var hub = mock(RealtimeEventHub.class);
        var listener = new ConversationRealtimeListener(hub);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();

        listener.on(new ConversationTurnCompleted(tenantId, conversationId,
                "provider-message-1", "WARM", "private inbound", "private reply"));

        var event = forClass(FrontendEvent.class);
        verify(hub).publish(event.capture());
        assertThat(event.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(event.getValue().aggregateId()).isEqualTo(conversationId);
        assertThat(event.getValue().type()).isEqualTo("conversation.updated");
        assertThat(event.getValue().data()).containsEntry("status", "BOT_REPLY")
                .containsEntry("providerMessageId", "provider-message-1")
                .doesNotContainValue("private inbound")
                .doesNotContainValue("private reply");
    }

    @Test
    void publishesMetadataOnlyUpdateWhenHandoffIsQueued() {
        var hub = mock(RealtimeEventHub.class);
        var listener = new ConversationRealtimeListener(hub);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var eventId = UUID.randomUUID();

        listener.on(new HandoffRequested(eventId, tenantId, conversationId,
                "CUSTOMER_REQUEST", java.time.Instant.now()));

        var event = forClass(FrontendEvent.class);
        verify(hub).publish(event.capture());
        assertThat(event.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(event.getValue().aggregateId()).isEqualTo(conversationId);
        assertThat(event.getValue().type()).isEqualTo("conversation.updated");
        assertThat(event.getValue().data()).containsEntry("status", "HUMAN_PENDING")
                .containsEntry("handoffEventId", eventId.toString());
    }

    @Test
    void publishesMetadataOnlyUpdateAfterMemoryRestart() {
        var hub = mock(RealtimeEventHub.class);
        var listener = new ConversationRealtimeListener(hub);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var resetAt = java.time.Instant.now();

        listener.on(new ConversationMemoryRestarted(tenantId, conversationId, 8, resetAt));

        var event = forClass(FrontendEvent.class);
        verify(hub).publish(event.capture());
        assertThat(event.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(event.getValue().aggregateId()).isEqualTo(conversationId);
        assertThat(event.getValue().type()).isEqualTo("conversation.updated");
        assertThat(event.getValue().data()).containsEntry("status", "BOT_CONTEXT_RESTARTED")
                .containsEntry("conversationVersion", 8L)
                .containsEntry("contextStartedAt", resetAt.toString());
    }
}
