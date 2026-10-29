package com.ford.service.handoffcontact.application;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public interface AlternateContactDeletionLedger {
    void record(DeletionMarker marker);

    enum DeletionReason { FORGET, RETENTION, STAGING_EXPIRY }

    record DeletionMarker(UUID markerId, UUID tenantId, UUID conversationId, UUID ticketId, UUID receiptId,
                          Instant deletedAt, DeletionReason reason) {
        public DeletionMarker {
            if (markerId == null || tenantId == null || conversationId == null || deletedAt == null || reason == null)
                throw new IllegalArgumentException("deletion marker identifiers and reason are required");
        }
    }

    static DeletionMarker marker(UUID tenantId, UUID conversationId, UUID ticketId, UUID receiptId,
                                 Instant deletedAt, DeletionReason reason) {
        try {
            var key = reason + ":" + tenantId + ":" + conversationId + ":" + ticketId + ":" + receiptId;
            var digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            var markerId = UUID.nameUUIDFromBytes(HexFormat.of().formatHex(digest).getBytes(StandardCharsets.UTF_8));
            return new DeletionMarker(markerId, tenantId, conversationId, ticketId, receiptId, deletedAt, reason);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
