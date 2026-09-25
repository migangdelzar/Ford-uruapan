package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.MemoryUpdateResult;

public interface ConversationMemoryPort {
    ConversationMemoryContext load(MemoryQuery query);

    void recordInbound(ConversationMemoryKey key, ConversationMessage message);

    void recordOutbound(ConversationMemoryKey key, ConversationMessage message);

    MemoryUpdateResult updateProjection(ConversationMemoryKey key,
                                        MemoryProjectionUpdate update,
                                        long expectedVersion);
}
