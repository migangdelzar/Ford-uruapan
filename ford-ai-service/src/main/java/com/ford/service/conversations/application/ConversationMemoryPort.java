package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.MemoryUpdateResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ConversationMemoryPort {
    ConversationMemoryContext load(MemoryQuery query);

    void recordInbound(ConversationMemoryKey key, ConversationMessage message);

    default List<ConversationMessage> loadTranscriptForRebase(ConversationMemoryKey key, Instant contextStartedAt,
                                                               int keepRecentCount, int maxRebaseMessages) {
        return List.of();
    }

    default void invalidate(ConversationMemoryKey key) {
    }

    default void resetContext(ConversationMemoryKey key, UUID actorId, Instant resetAt) {
        throw new UnsupportedOperationException("conversation memory reset is not supported");
    }

    MemoryUpdateResult updateProjection(ConversationMemoryKey key,
                                        MemoryProjectionUpdate update,
                                        long expectedVersion);
}
