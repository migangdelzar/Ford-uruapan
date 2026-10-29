package com.ford.service.realtime.application;

import com.ford.service.realtime.domain.FrontendEvent;
import com.ford.service.shared.ConversationTurnCompleted;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Notifies connected CRM clients after the assistant reply is committed. */
@Component
public class ConversationTurnRealtimeListener {

    private final RealtimeEventHub hub;

    public ConversationTurnRealtimeListener(RealtimeEventHub hub) {
        this.hub = hub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ConversationTurnCompleted completed) {
        hub.publish(new FrontendEvent(
                UUID.randomUUID(),
                completed.tenantId(),
                completed.conversationId(),
                "conversation.updated",
                Instant.now(),
                Map.of("conversationId", completed.conversationId())));
    }
}
