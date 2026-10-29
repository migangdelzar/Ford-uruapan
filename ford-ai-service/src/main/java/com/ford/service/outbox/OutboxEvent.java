package com.ford.service.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID id, UUID tenantId, String aggregateType, UUID aggregateId,
                          String eventType, String payload, Status status, int attempts,
                          Instant availableAt, Instant claimedAt, Instant leaseUntil,
                          String idempotencyKey, String providerDeliveryId, String lastErrorCategory) {
    public enum Status { PENDING, PROCESSING, DELIVERED, DEAD_LETTER }

    public OutboxEvent(UUID id, UUID tenantId, String aggregateType, UUID aggregateId,
                       String eventType, String payload, Status status, int attempts,
                       Instant availableAt, Instant claimedAt) {
        this(id, tenantId, aggregateType, aggregateId, eventType, payload, status, attempts,
                availableAt, claimedAt, null, "outbox:" + id, null, null);
    }

    public OutboxEvent {
        if (id == null || tenantId == null || aggregateId == null || eventType == null || payload == null) {
            throw new IllegalArgumentException("Outbox identity and payload are required");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    public OutboxEvent claim() {
        return claim(Instant.now(), Duration.ofSeconds(30));
    }

    public OutboxEvent claim(Instant now, Duration lease) {
        return new OutboxEvent(id, tenantId, aggregateType, aggregateId, eventType, payload,
                Status.PROCESSING, attempts + 1, availableAt, now, now.plus(lease),
                idempotencyKey, providerDeliveryId, lastErrorCategory);
    }

    public OutboxEvent delivered() {
        return new OutboxEvent(id, tenantId, aggregateType, aggregateId, eventType, payload,
                Status.DELIVERED, attempts, availableAt, claimedAt, null,
                idempotencyKey, providerDeliveryId, lastErrorCategory);
    }

    public OutboxEvent failed(String error, boolean deadLetter) {
        return new OutboxEvent(id, tenantId, aggregateType, aggregateId, eventType, payload,
                deadLetter ? Status.DEAD_LETTER : Status.PENDING, attempts, Instant.now(), claimedAt,
                null, idempotencyKey, providerDeliveryId, deadLetter ? "permanent" : "transient");
    }
}
