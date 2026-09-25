package com.ford.service.handoff;

import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.handoff.application.HandoffNotificationListener;
import com.ford.service.handoff.application.port.out.HandoffRepository;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HandoffNotificationListenerTest {

    @Test
    void recordsTheDurableQueueEntryWithoutClaimingANotificationWasSent() {
        var conversationId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var ticket = new HandoffTicket(UUID.randomUUID(), tenantId, conversationId, null,
                HandoffStatus.HUMAN_PENDING, null, "assistant_requested_handoff", 0,
                Instant.now(), null, null);
        var repository = mock(HandoffRepository.class);
        when(repository.findByConversation(conversationId)).thenReturn(ticket);
        var listener = new HandoffNotificationListener(repository);
        var event = new HandoffRequested(eventId, tenantId, conversationId,
                "assistant_requested_handoff", Instant.parse("2026-09-19T18:00:00Z"));

        listener.on(event);

        verify(repository).recordSystemAudit(
                "HANDOFF_QUEUE_ITEM_CREATED", ticket, eventId, event.occurredAt());
    }
}
