package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.MemoryQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public final class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    private final ConversationMemoryPort memory;
    private final MemoryProperties properties;

    public ConversationMemoryService(ConversationMemoryPort memory, MemoryProperties properties) {
        this.memory = Objects.requireNonNull(memory);
        this.properties = Objects.requireNonNull(properties);
    }

    public ConversationMemoryContext load(MemoryQuery query) {
        if (!properties.enabled()) {
            return ConversationMemoryContext.empty(query.key());
        }
        var boundedQuery = new MemoryQuery(query.key(), query.latestMessage(),
                properties.recentMessageLimit(), properties.semanticLimit());
        try {
            return memory.load(boundedQuery);
        } catch (RuntimeException exception) {
            log.warn("Conversation memory load failed: conversationId={} tenantId={} exceptionType={}",
                    query.key().conversationId(), query.key().tenantId(),
                    exception.getClass().getSimpleName());
            return ConversationMemoryContext.empty(query.key());
        }
    }
}
