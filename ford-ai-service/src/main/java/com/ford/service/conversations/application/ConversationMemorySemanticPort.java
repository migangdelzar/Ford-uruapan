package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryFact;

import java.util.List;

public interface ConversationMemorySemanticPort {
    List<MemoryFact> search(ConversationMemoryKey key, String query, int limit);

    void upsert(ConversationMemoryKey key, MemoryFact fact);
}
