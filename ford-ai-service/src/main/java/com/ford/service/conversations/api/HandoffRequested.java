package com.ford.service.conversations.api;

import org.springframework.modulith.NamedInterface;

import java.time.Instant;
import java.util.UUID;

/** Notification contract emitted after a CAS-protected conversation handoff. */
@NamedInterface
public record HandoffRequested(
        UUID eventId,
        UUID tenantId,
        UUID conversationId,
        String reason,
        Instant occurredAt,
        SpecialistHandoffPayload payload,
        InternalHandoffNote internalNote) {

    public HandoffRequested(UUID eventId, UUID tenantId, UUID conversationId, String reason, Instant occurredAt,
                            SpecialistHandoffPayload payload) {
        this(eventId, tenantId, conversationId, reason, occurredAt, payload, null);
    }

    public HandoffRequested(UUID eventId, UUID tenantId, UUID conversationId,
                            String reason, Instant occurredAt) {
        this(eventId, tenantId, conversationId, reason, occurredAt,
                new SpecialistHandoffPayload(reason, java.util.Optional.empty(),
                        java.util.Optional.empty(), java.util.Optional.empty()), null);
    }

    public HandoffRequested {
        if (eventId == null || tenantId == null || conversationId == null || occurredAt == null
                || reason == null || reason.isBlank() || payload == null
                || !reason.equals(payload.reason())) {
            throw new IllegalArgumentException("Handoff notification identity and reason are required");
        }
    }
}
