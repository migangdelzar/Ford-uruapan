package com.ford.service.handoff.application;

import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.handoff.application.port.out.HandoffRepository;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Audits the durable handoff queue entry; this does not claim an external notification was sent. */
@Component
public class HandoffNotificationListener {

    private final HandoffRepository repository;

    public HandoffNotificationListener(HandoffRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @ApplicationModuleListener
    public void on(HandoffRequested event) {
        var ticket = repository.findByConversation(event.conversationId());
        repository.recordSystemAudit(
                "HANDOFF_QUEUE_ITEM_CREATED", ticket, event.eventId(), event.occurredAt());
    }
}
