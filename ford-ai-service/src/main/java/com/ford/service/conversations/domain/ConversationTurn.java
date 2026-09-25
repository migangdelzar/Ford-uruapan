package com.ford.service.conversations.domain;

public record ConversationTurn(String text) {
    public ConversationTurn {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Conversation turn text is required");
        }
    }
}
