package com.ford.service.conversations.application.graph;

import java.util.Objects;
import java.util.UUID;

public record ConversationTurnReference(UUID tenantId, UUID conversationId, UUID receiptId) {

    public ConversationTurnReference {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(conversationId);
        Objects.requireNonNull(receiptId);
    }

    public String checkpointThreadId() {
        return tenantId + ":" + conversationId;
    }
}
