package com.ford.service.handoffcontact.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlternateContactStagingPort {
    void save(UUID tenantId, UUID conversationId, UUID receiptId,
              EncryptedContactEnvelope envelope, Instant collectedAt, Instant expiresAt);

    Optional<EncryptedContactEnvelope> find(UUID tenantId, UUID conversationId, UUID receiptId, Instant now);

    boolean delete(UUID tenantId, UUID conversationId, UUID receiptId);

    List<ExpiredStagingReference> findExpired(Instant now, int limit);

    record ExpiredStagingReference(UUID tenantId, UUID conversationId, UUID receiptId, Instant expiresAt) { }
}
