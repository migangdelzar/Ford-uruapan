package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMemoryRecall;
import com.ford.service.conversations.domain.ConversationMessage;

import java.time.Instant;
import java.util.List;

public final class NoOpConversationMemoryRecallPort implements ConversationMemoryRecallPort {

    @Override
    public List<ConversationMemoryRecall> search(ConversationMemoryKey key, String query,
                                                 Instant before, int limit, double minimumSimilarity) {
        return List.of();
    }

    @Override
    public void upsert(ConversationMemoryKey key, ConversationMessage message, String redactedContent,
                       String embeddingModel, float[] embedding) {
        // Semantic indexing is optional and must never block conversation processing.
    }
}
