package com.ford.service.outbox;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OutboxStore {
    void append(OutboxEvent event);
    Optional<OutboxEvent> claimNext(Instant now);
    void markDelivered(UUID eventId, String providerMessageId);
    void markFailed(UUID eventId, String error, boolean deadLetter);
}
