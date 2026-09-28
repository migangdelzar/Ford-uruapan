package com.ford.service.conversations.domain;

import java.util.List;
import java.time.Instant;

public record ConversationMemoryContext(
        ConversationMemoryKey key,
        String summary,
        List<ConversationMessage> recentMessages,
        List<MemoryFact> durableFacts,
        long memoryVersion,
        Instant updatedAt,
        List<ConversationMemoryRecall> recalledTurns) {

    public ConversationMemoryContext(ConversationMemoryKey key, String summary,
                                     List<ConversationMessage> recentMessages,
                                     List<MemoryFact> durableFacts, long memoryVersion) {
        this(key, summary, recentMessages, durableFacts, memoryVersion, null, List.of());
    }

    public ConversationMemoryContext(ConversationMemoryKey key, String summary,
                                     List<ConversationMessage> recentMessages,
                                     List<MemoryFact> durableFacts, long memoryVersion,
                                     Instant updatedAt) {
        this(key, summary, recentMessages, durableFacts, memoryVersion, updatedAt, List.of());
    }

    public ConversationMemoryContext {
        summary = summary == null ? "" : summary;
        recentMessages = List.copyOf(recentMessages == null ? List.of() : recentMessages);
        durableFacts = List.copyOf(durableFacts == null ? List.of() : durableFacts);
        recalledTurns = List.copyOf(recalledTurns == null ? List.of() : recalledTurns);
    }

    public static ConversationMemoryContext empty(ConversationMemoryKey key) {
        return new ConversationMemoryContext(key, "", List.of(), List.of(), 0);
    }
}
