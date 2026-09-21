package com.ford.service.conversations.domain;

public record MemoryQuery(
        ConversationMemoryKey key,
        String latestMessage,
        int recentMessageLimit,
        int semanticLimit) {

    public MemoryQuery(ConversationMemoryKey key, String latestMessage) {
        this(key, latestMessage, 20, 5);
    }

    public MemoryQuery {
        if (recentMessageLimit < 0) {
            throw new IllegalArgumentException("recentMessageLimit cannot be negative");
        }
        if (semanticLimit < 0) {
            throw new IllegalArgumentException("semanticLimit cannot be negative");
        }
    }
}
