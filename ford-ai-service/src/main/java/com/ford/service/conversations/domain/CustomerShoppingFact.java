package com.ford.service.conversations.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CustomerShoppingFact(UUID identityId, CustomerShoppingFactType type, String subjectKey,
                                   String value, CustomerShoppingFactState state, UUID sourceConversationId,
                                   UUID sourceReceiptId, Instant sourceReceivedAt, int sourceVersion,
                                   Instant statedAt, Instant updatedAt, Instant expiresAt, long version) {
    public CustomerShoppingFact {
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subjectKey, "subjectKey");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(sourceConversationId, "sourceConversationId");
        Objects.requireNonNull(sourceReceiptId, "sourceReceiptId");
        Objects.requireNonNull(sourceReceivedAt, "sourceReceivedAt");
        Objects.requireNonNull(statedAt, "statedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (subjectKey.isBlank() || subjectKey.length() > 120 || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("customer shopping fact is outside allowed bounds");
        }
        if (state == CustomerShoppingFactState.DECLINED && expiresAt != null) {
            throw new IllegalArgumentException("declined facts must not expire automatically");
        }
    }
}
