package com.ford.service.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID id, UUID tenantId, String aggregateType, UUID aggregateId,
                          String eventType, String payload, Status status, int attempts,
                          Instant availableAt, Instant claimedAt) {
    public enum Status { PENDING, PROCESSING, DELIVERED, DEAD_LETTER }

    public OutboxEvent {
        if (id == null || tenantId == null || aggregateId == null || eventType == null || payload == null) {
            throw new IllegalArgumentException("Outbox identity and payload are required");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    public OutboxEvent claim() {
        return new OutboxEvent(id, tenantId, aggregateType, aggregateId, eventType, payload,
                Status.PROCESSING, attempts + 1, availableAt, Instant.now());
    }

    public OutboxEvent delivered() {
        return new OutboxEvent(id, tenantId, aggregateType, aggregateId, eventType, payload,
                Status.DELIVERED, attempts, availableAt, claimedAt);
    }

    public OutboxEvent failed(String error, boolean deadLetter) {
        return new OutboxEvent(id, tenantId, aggregateType, aggregateId, eventType, payload,
                deadLetter ? Status.DEAD_LETTER : Status.PENDING, attempts, Instant.now(), claimedAt);
    }
}
