package com.ford.service.handoff.application;

import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.handoff.application.port.out.HandoffRepository;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Completes the durable operator-notification reaction for an assistant handoff. */
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
                "HANDOFF_NOTIFICATION_REQUESTED", ticket, event.eventId(), event.occurredAt());
    }
}
