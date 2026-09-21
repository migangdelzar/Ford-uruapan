package com.ford.service.conversations.api;

import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationMemoryContext;

import java.util.UUID;

public record ConversationTurnRequest(
        UUID tenantId,
        UUID conversationId,
        String message,
        ConversationState state,
        ConversationMemoryContext memory) {

    public ConversationTurnRequest(UUID tenantId, UUID conversationId,
                                   String message, ConversationState state) {
        this(tenantId, conversationId, message, state, null);
    }
}
