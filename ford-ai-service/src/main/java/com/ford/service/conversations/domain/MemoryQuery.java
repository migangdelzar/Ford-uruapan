package com.ford.service.conversations.domain;

public record MemoryQuery(
        ConversationMemoryKey key,
        String latestMessage,
        String currentProviderMessageId,
        int recentMessageLimit,
        int semanticLimit) {

    public MemoryQuery(ConversationMemoryKey key, String latestMessage) {
        this(key, latestMessage, null, 20, 5);
    }

    public MemoryQuery(ConversationMemoryKey key, String latestMessage,
                       String currentProviderMessageId) {
        this(key, latestMessage, currentProviderMessageId, 20, 5);
    }

    public MemoryQuery(ConversationMemoryKey key, String latestMessage,
                       int recentMessageLimit, int semanticLimit) {
        this(key, latestMessage, null, recentMessageLimit, semanticLimit);
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
