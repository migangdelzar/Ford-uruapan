package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryFact;

import java.util.List;

public final class NoOpConversationMemorySemanticPort implements ConversationMemorySemanticPort {
    @Override
    public List<MemoryFact> search(ConversationMemoryKey key, String query, int limit) {
        return List.of();
    }

    @Override
    public void upsert(ConversationMemoryKey key, MemoryFact fact) {
    }
}
