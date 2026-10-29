package com.ford.service.handoff.domain;

import com.ford.service.conversations.api.BdcHandoffPacket;
import com.ford.service.conversations.api.InternalHandoffNote;

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
        Instant closedAt,
        BdcHandoffPacket packet,
        InternalHandoffNote internalNote) {

    public HandoffTicket {
        packet = packet == null ? BdcHandoffPacket.empty() : packet;
    }

    public HandoffTicket(UUID id, UUID tenantId, UUID conversationId, UUID leadId,
                         HandoffStatus status, HandoffPriority priority, UUID assignedOperatorId, String reason,
                         String idempotencyKey, long version, Instant createdAt, Instant claimedAt, Instant closedAt,
                         BdcHandoffPacket packet) {
        this(id, tenantId, conversationId, leadId, status, priority, assignedOperatorId, reason,
                idempotencyKey, version, createdAt, claimedAt, closedAt, packet, null);
    }

    public HandoffTicket(UUID id, UUID tenantId, UUID conversationId, UUID leadId,
                         HandoffStatus status, HandoffPriority priority, UUID assignedOperatorId, String reason,
                         String idempotencyKey, long version, Instant createdAt, Instant claimedAt, Instant closedAt) {
        this(id, tenantId, conversationId, leadId, status, priority, assignedOperatorId, reason,
                idempotencyKey, version, createdAt, claimedAt, closedAt, BdcHandoffPacket.empty(), null);
    }

    public HandoffTicket(UUID id, UUID tenantId, UUID conversationId, UUID leadId,
                         HandoffStatus status, UUID assignedOperatorId, String reason, long version,
                         Instant createdAt, Instant claimedAt, Instant closedAt) {
        this(id, tenantId, conversationId, leadId, status, HandoffPriority.NORMAL, assignedOperatorId,
                reason, null, version, createdAt, claimedAt, closedAt, BdcHandoffPacket.empty(), null);
    }
}
