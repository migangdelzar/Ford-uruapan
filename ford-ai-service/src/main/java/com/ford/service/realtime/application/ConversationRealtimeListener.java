package com.ford.service.realtime.application;

import com.ford.service.conversations.api.ConversationMemoryRestarted;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.realtime.domain.FrontendEvent;
import com.ford.service.shared.ConversationTurnCompleted;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Refreshes operator views after durable reply or handoff commits without exposing customer text. */
@Component
public class ConversationRealtimeListener {

    private final RealtimeEventHub hub;

    public ConversationRealtimeListener(RealtimeEventHub hub) {
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
                Map.of("status", "BOT_REPLY", "providerMessageId", completed.providerMessageId())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(HandoffRequested requested) {
        hub.publish(new FrontendEvent(
                UUID.randomUUID(),
                requested.tenantId(),
                requested.conversationId(),
                "conversation.updated",
                Instant.now(),
                Map.of("status", "HUMAN_PENDING", "handoffEventId", requested.eventId().toString())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ConversationMemoryRestarted restarted) {
        hub.publish(new FrontendEvent(
                UUID.randomUUID(), restarted.tenantId(), restarted.conversationId(),
                "conversation.updated", Instant.now(),
                Map.of("status", "BOT_CONTEXT_RESTARTED", "conversationVersion", restarted.conversationVersion(),
                        "contextStartedAt", restarted.contextStartedAt().toString())));
    }
}
