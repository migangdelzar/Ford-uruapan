package com.ford.service.handoff.api;

import org.springframework.modulith.NamedInterface;

import java.time.Instant;
import java.util.UUID;

@NamedInterface
public record HandoffActionRequested(
        UUID tenantId,
        UUID ticketId,
        UUID conversationId,
        UUID operatorId,
        String action,
        long version,
        Instant occurredAt) {
}
