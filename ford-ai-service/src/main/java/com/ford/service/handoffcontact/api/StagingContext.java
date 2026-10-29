package com.ford.service.handoffcontact.api;

import java.util.Objects;
import java.util.UUID;

public record StagingContext(UUID tenantId, UUID conversationId, UUID receiptId) {
    public StagingContext {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(conversationId, "conversationId is required");
        Objects.requireNonNull(receiptId, "receiptId is required");
    }
}
