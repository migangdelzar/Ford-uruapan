package com.ford.service.realtime;

import com.ford.service.realtime.application.ConversationTurnRealtimeListener;
import com.ford.service.realtime.application.RealtimeEventHub;
import com.ford.service.realtime.domain.FrontendEvent;
import com.ford.service.shared.ConversationTurnCompleted;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConversationTurnRealtimeListenerTest {

    @Test
    void publishesOnlyConversationRefreshMetadataAfterBotReplyCommits() {
        var hub = mock(RealtimeEventHub.class);
        var listener = new ConversationTurnRealtimeListener(hub);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();

        listener.on(new ConversationTurnCompleted(tenantId, conversationId, "provider-message",
                "WARM", "private inbound text", "private assistant text"));

        var event = forClass(FrontendEvent.class);
        verify(hub).publish(event.capture());
        assertThat(event.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(event.getValue().aggregateId()).isEqualTo(conversationId);
        assertThat(event.getValue().type()).isEqualTo("conversation.updated");
        assertThat(event.getValue().data()).containsEntry("conversationId", conversationId);
        assertThat(event.getValue().data()).doesNotContainKey("inboundText").doesNotContainKey("assistantText");
    }
}
