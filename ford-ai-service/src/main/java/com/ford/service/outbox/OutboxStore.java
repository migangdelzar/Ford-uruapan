package com.ford.service.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OutboxStore {
    void append(OutboxEvent event);
    Optional<OutboxEvent> claimNext(Instant now);
    void markDelivered(UUID eventId, String providerMessageId);
    void markFailed(UUID eventId, String error, boolean deadLetter);

    default Optional<OutboxEvent> claimNext(Instant now, Duration lease) {
        return claimNext(now);
    }

    default int reclaimExpired(Instant now) {
        return 0;
    }

    default void markDelivered(UUID eventId, String providerMessageId, Instant deliveredAt) {
        markDelivered(eventId, providerMessageId);
    }

    default void markDelivered(UUID eventId, String providerMessageId, Instant deliveredAt, Instant leaseUntil) {
        markDelivered(eventId, providerMessageId, deliveredAt);
    }

    default void markRetry(UUID eventId, String category, Instant nextAttemptAt) {
        markFailed(eventId, category, false);
    }

    default void markRetry(UUID eventId, String category, Instant nextAttemptAt,
                           String reason, Instant leaseUntil) {
        markRetry(eventId, category, nextAttemptAt);
    }

    default void moveToDeadLetter(UUID eventId, String category, String reason) {
        markFailed(eventId, reason, true);
    }

    default void moveToDeadLetter(UUID eventId, String category, String reason, Instant leaseUntil) {
        moveToDeadLetter(eventId, category, reason);
    }
}
