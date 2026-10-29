package com.ford.service.conversations.domain;

import java.time.Instant;
import java.util.UUID;

/** A draft for operator review only; it has no delivery API or outbox identity. */
public record ShadowDraft(UUID id, UUID tenantId, UUID conversationId, UUID receiptId,
                             String inboundProviderMessageId, String text, Instant createdAt,
                             Instant expiresAt, String reviewStatus) {
    @Override
    public String toString() {
        return "ShadowDraft[id=" + id + ", reviewStatus=" + reviewStatus + "]";
    }
}
