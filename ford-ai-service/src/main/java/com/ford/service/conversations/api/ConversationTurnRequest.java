package com.ford.service.conversations.api;

import com.ford.service.conversations.domain.ConversationState;

import java.util.UUID;

public record ConversationTurnRequest(
        UUID tenantId,
        UUID conversationId,
        String message,
        ConversationState state) {
}
