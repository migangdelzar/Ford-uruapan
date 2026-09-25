package com.ford.service.conversations.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository {
    Optional<StoredCommand> find(UUID tenantId, String actorId, String operation, String key);

    boolean reserve(UUID tenantId, String actorId, String operation, String key, String requestHash,
                    Instant createdAt);

    void complete(UUID tenantId, String actorId, String operation, String key, String responseJson,
                  Instant completedAt);

    record StoredCommand(String requestHash, String responseJson) {
    }
}
