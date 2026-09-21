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
    private final ConversationMemorySemanticPort semanticMemory;

    public ConversationMemoryService(ConversationMemoryPort memory, MemoryProperties properties) {
        this(memory, properties, new NoOpConversationMemorySemanticPort());
    }

    public ConversationMemoryService(ConversationMemoryPort memory,
                                     MemoryProperties properties,
                                     ConversationMemorySemanticPort semanticMemory) {
        this.memory = Objects.requireNonNull(memory);
        this.properties = Objects.requireNonNull(properties);
        this.semanticMemory = Objects.requireNonNull(semanticMemory);
    }

    public ConversationMemoryContext load(MemoryQuery query) {
        if (!properties.enabled()) {
            return ConversationMemoryContext.empty(query.key());
        }
        var boundedQuery = new MemoryQuery(query.key(), query.latestMessage(), query.currentProviderMessageId(),
                properties.recentMessageLimit(), properties.semanticLimit());
        try {
            var context = memory.load(boundedQuery);
            if (!properties.semanticEnabled() || query.latestMessage() == null
                    || query.latestMessage().isBlank()) {
                return context;
            }
            try {
                var semanticFacts = semanticMemory.search(query.key(), query.latestMessage(),
                        properties.semanticLimit());
                var facts = java.util.stream.Stream.concat(context.durableFacts().stream(), semanticFacts.stream())
                        .limit(properties.factLimit())
                        .toList();
                return new ConversationMemoryContext(context.key(), context.summary(),
                        context.recentMessages(), facts, context.memoryVersion());
            } catch (RuntimeException exception) {
                log.warn("Semantic conversation memory search failed: conversationId={} tenantId={} exceptionType={}",
                        query.key().conversationId(), query.key().tenantId(),
                        exception.getClass().getSimpleName());
                return context;
            }
        } catch (RuntimeException exception) {
            log.warn("Conversation memory load failed: conversationId={} tenantId={} exceptionType={}",
                    query.key().conversationId(), query.key().tenantId(),
                    exception.getClass().getSimpleName());
            return ConversationMemoryContext.empty(query.key());
        }
    }
}
