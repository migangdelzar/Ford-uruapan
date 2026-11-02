package com.ford.service.conversations.domain;

public enum ConversationState {
    BOT_ACTIVE,
    LEAD_CAPTURE,
    HUMAN_PENDING,
    HUMAN_ACTIVE,
    CLOSED;

    public ConversationState transitionTo(ConversationState target) {
        if (this == target) {
            return this;
        }
        if (this == CLOSED || (this == HUMAN_ACTIVE && target != CLOSED)
                || (this == BOT_ACTIVE && target == HUMAN_ACTIVE)
                || (this == LEAD_CAPTURE && target == BOT_ACTIVE)
                || (this == HUMAN_PENDING && target == BOT_ACTIVE)) {
            throw new IllegalStateException("Invalid conversation transition: " + this + " -> " + target);
        }
        return target;
    }
}
