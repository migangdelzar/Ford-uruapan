package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMemoryRecall;
import com.ford.service.conversations.domain.ConversationMessage;

import java.time.Instant;
import java.util.List;

public interface ConversationMemoryRecallPort {

    List<ConversationMemoryRecall> search(
            ConversationMemoryKey key,
            String query,
            Instant before,
            int limit,
            double minimumSimilarity);

    void upsert(
            ConversationMemoryKey key,
            ConversationMessage message,
            String redactedContent,
            String embeddingModel,
            float[] embedding);
}
