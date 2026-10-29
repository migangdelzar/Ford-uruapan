package com.ford.service.conversations.application;

public enum ConversationTurnState {
    ACCEPTED,
    DEBOUNCED,
    PROCESSING,
    RETRY_WAIT,
    REPLY_COMMITTED,
    SAFE_FALLBACK_REVIEW,
    HUMAN_PENDING,
    DELIVERY_PENDING,
    DELIVERED,
    DELIVERY_FAILED,
    NEEDS_OPERATOR,
    DUPLICATE,
    OUT_OF_SCOPE,
    SHADOW_DRAFT
}
