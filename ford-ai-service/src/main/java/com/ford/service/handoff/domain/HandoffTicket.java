package com.ford.service.handoff.domain;

import java.time.Instant;
import java.util.UUID;

public record HandoffTicket(
        UUID id,
        UUID tenantId,
        UUID conversationId,
        UUID leadId,
        HandoffStatus status,
        HandoffPriority priority,
        UUID assignedOperatorId,
        String reason,
        String idempotencyKey,
        long version,
        Instant createdAt,
        Instant claimedAt,
        Instant closedAt) {

    public HandoffTicket(UUID id, UUID tenantId, UUID conversationId, UUID leadId,
                         HandoffStatus status, UUID assignedOperatorId, String reason, long version,
                         Instant createdAt, Instant claimedAt, Instant closedAt) {
        this(id, tenantId, conversationId, leadId, status, HandoffPriority.NORMAL, assignedOperatorId,
                reason, null, version, createdAt, claimedAt, closedAt);
    }
}
