package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;

public final class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    private final ConversationMemoryPort memory;
    private final MemoryProperties properties;
    private final ConversationMemorySemanticPort semanticMemory;
    private final ConversationMemoryProjector projector;
    private final ApplicationEventPublisher eventPublisher;

    public ConversationMemoryService(ConversationMemoryPort memory, MemoryProperties properties) {
        this(memory, properties, new NoOpConversationMemorySemanticPort(), event -> { });
    }

    public ConversationMemoryService(ConversationMemoryPort memory,
                                     MemoryProperties properties,
                                     ConversationMemorySemanticPort semanticMemory) {
        this(memory, properties, semanticMemory, event -> { });
    }

    public ConversationMemoryService(ConversationMemoryPort memory,
                                     MemoryProperties properties,
                                     ConversationMemorySemanticPort semanticMemory,
                                     ApplicationEventPublisher eventPublisher) {
        this.memory = Objects.requireNonNull(memory);
        this.properties = Objects.requireNonNull(properties);
        this.semanticMemory = Objects.requireNonNull(semanticMemory);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.projector = new ConversationMemoryProjector(properties);
    }

    public ConversationMemoryContext load(MemoryQuery query) {
        if (!properties.enabled()) {
            return ConversationMemoryContext.empty(query.key());
        }
        var boundedQuery = new MemoryQuery(query.key(), query.latestMessage(), query.currentProviderMessageId(),
                properties.promptRecentMessageLimit(), properties.semanticLimit());
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

    public void recordInbound(ConversationMemoryKey key, UUID messageId, String providerMessageId,
                              String content, java.time.Instant occurredAt) {
        if (!properties.enabled() || content == null || content.isBlank()) {
            return;
        }
        try {
            var message = new ConversationMessage(messageId, key.conversationId(),
                    ConversationMessage.Direction.INBOUND, providerMessageId, content, occurredAt);
            memory.recordInbound(key, message);
            publishIndexRequest(key, message);
        } catch (RuntimeException exception) {
            log.warn("Inbound conversation memory write failed: conversationId={} exceptionType={}",
                    key.conversationId(), exception.getClass().getSimpleName());
            invalidate(key);
        }
    }

    public void recordOutbound(ConversationMemoryKey key, UUID messageId, String providerMessageId,
                               String content, java.time.Instant occurredAt) {
        if (!properties.enabled() || content == null || content.isBlank()) {
            return;
        }
        try {
            var message = new ConversationMessage(messageId, key.conversationId(),
                    ConversationMessage.Direction.OUTBOUND, providerMessageId, content, occurredAt);
            memory.recordOutbound(key, message);
            publishIndexRequest(key, message);
        } catch (RuntimeException exception) {
            log.warn("Outbound conversation memory write failed: conversationId={} exceptionType={}",
                    key.conversationId(), exception.getClass().getSimpleName());
            invalidate(key);
        }
    }

    public MemoryProjectionUpdate project(ConversationMemoryContext context, String latestUserMessage,
                                          UUID sourceMessageId) {
        return projector.project(context, latestUserMessage, sourceMessageId);
    }

    public void invalidate(ConversationMemoryKey key) {
        try {
            memory.invalidate(key);
        } catch (RuntimeException exception) {
            log.warn("Conversation memory invalidation failed: conversationId={} exceptionType={}",
                    key.conversationId(), exception.getClass().getSimpleName());
        }
    }

    private void publishIndexRequest(ConversationMemoryKey key, ConversationMessage message) {
        try {
            eventPublisher.publishEvent(new com.ford.service.conversations.api.ConversationMemoryIndexRequested(key, message));
        } catch (RuntimeException exception) {
            log.warn("Conversation memory index event publication failed: conversationId={} exceptionType={}",
                    key.conversationId(), exception.getClass().getSimpleName());
        }
    }
}
