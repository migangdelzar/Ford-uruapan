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
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;

public final class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    private final ConversationMemoryPort memory;
    private final MemoryProperties properties;
    private final ConversationMemorySemanticPort semanticMemory;
    private final ConversationMemoryRecallPort recall;
    private final ConversationMemoryRecallPolicy recallPolicy;
    private final ConversationMemoryProjector projector;
    private final ApplicationEventPublisher eventPublisher;
    private final ConversationRuntimeSettingsService runtimeSettings;

    public ConversationMemoryService(ConversationMemoryPort memory, MemoryProperties properties) {
        this(memory, properties, new NoOpConversationMemorySemanticPort(),
                new NoOpConversationMemoryRecallPort(), event -> { });
    }

    public ConversationMemoryService(ConversationMemoryPort memory,
                                     MemoryProperties properties,
                                     ConversationMemorySemanticPort semanticMemory) {
        this(memory, properties, semanticMemory, new NoOpConversationMemoryRecallPort(), event -> { });
    }

    public ConversationMemoryService(ConversationMemoryPort memory,
                                     MemoryProperties properties,
                                     ConversationMemorySemanticPort semanticMemory,
                                     ApplicationEventPublisher eventPublisher) {
        this(memory, properties, semanticMemory, new NoOpConversationMemoryRecallPort(), eventPublisher,
                ConversationMemoryCompactionPort.unavailable());
    }

    public ConversationMemoryService(ConversationMemoryPort memory,
                                     MemoryProperties properties,
                                     ConversationMemorySemanticPort semanticMemory,
                                     ConversationMemoryRecallPort recall,
                                     ApplicationEventPublisher eventPublisher) {
        this(memory, properties, semanticMemory, recall, eventPublisher,
                ConversationMemoryCompactionPort.unavailable());
    }

    public ConversationMemoryService(ConversationMemoryPort memory,
                                     MemoryProperties properties,
                                     ConversationMemorySemanticPort semanticMemory,
                                     ConversationMemoryRecallPort recall,
                                     ApplicationEventPublisher eventPublisher,
                                     ConversationMemoryCompactionPort compactor) {
        this(memory, properties, semanticMemory, recall, eventPublisher, compactor, null);
    }

    public ConversationMemoryService(ConversationMemoryPort memory,
                                     MemoryProperties properties,
                                     ConversationMemorySemanticPort semanticMemory,
                                     ConversationMemoryRecallPort recall,
                                     ApplicationEventPublisher eventPublisher,
                                     ConversationMemoryCompactionPort compactor,
                                     ConversationRuntimeSettingsService runtimeSettings) {
        this.memory = Objects.requireNonNull(memory);
        this.properties = Objects.requireNonNull(properties);
        this.semanticMemory = Objects.requireNonNull(semanticMemory);
        this.recall = Objects.requireNonNull(recall);
        this.recallPolicy = new ConversationMemoryRecallPolicy();
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.projector = new ConversationMemoryProjector(properties, Objects.requireNonNull(compactor));
        this.runtimeSettings = runtimeSettings;
    }

    public ConversationMemoryContext load(MemoryQuery query) {
        var runtime = currentSettings(query.key().tenantId());
        if (!(runtime == null ? properties.enabled() : runtime.memoryEnabled())) {
            return ConversationMemoryContext.empty(query.key());
        }
        var promptLimit = runtime == null ? properties.promptRecentMessageLimit()
                : Math.min(properties.recentMessageLimit(), runtime.promptRecentMessageLimit());
        var boundedQuery = new MemoryQuery(query.key(), query.latestMessage(), query.currentProviderMessageId(),
                promptLimit, properties.semanticLimit());
        try {
            var context = memory.load(boundedQuery);
            if (!properties.semanticEnabled() || query.latestMessage() == null
                    || query.latestMessage().isBlank()) {
                // Continue to optional archived-turn recall below.
            } else {
                try {
                    var semanticFacts = semanticMemory.search(query.key(), query.latestMessage(),
                            properties.semanticLimit());
                    var facts = java.util.stream.Stream.concat(context.durableFacts().stream(), semanticFacts.stream())
                            .limit(properties.factLimit())
                            .toList();
                    context = new ConversationMemoryContext(context.key(), context.summary(),
                            context.recentMessages(), facts, context.memoryVersion(), context.updatedAt(),
                            context.recalledTurns(), context.contextStartedAt());
                } catch (RuntimeException exception) {
                    log.warn("Semantic conversation memory search failed: conversationId={} tenantId={} exceptionType={}",
                            query.key().conversationId(), query.key().tenantId(),
                            exception.getClass().getSimpleName());
                }
            }
            if (properties.semanticRecallEnabled()
                    && query.latestMessage() != null
                    && recallPolicy.shouldRecall(query.latestMessage(), context)) {
                try {
                    var recalled = recall.search(query.key(), query.latestMessage(), Instant.now(),
                            properties.semanticRecallLimit(), properties.semanticRecallMinimumSimilarity());
                    context = new ConversationMemoryContext(context.key(), context.summary(),
                            context.recentMessages(), context.durableFacts(), context.memoryVersion(),
                            context.updatedAt(), recalled, context.contextStartedAt());
                } catch (RuntimeException exception) {
                    log.warn("Conversation semantic recall failed: conversationId={} tenantId={} exceptionType={}",
                            query.key().conversationId(), query.key().tenantId(),
                            exception.getClass().getSimpleName());
                }
            }
            return context;
        } catch (RuntimeException exception) {
            log.warn("Conversation memory load failed: conversationId={} tenantId={} exceptionType={}",
                    query.key().conversationId(), query.key().tenantId(),
                    exception.getClass().getSimpleName());
            return ConversationMemoryContext.empty(query.key());
        }
    }

    public void recordInbound(ConversationMemoryKey key, UUID messageId, String providerMessageId,
                              String content, java.time.Instant occurredAt) {
        var runtime = currentSettings(key.tenantId());
        if (!(runtime == null ? properties.enabled() : runtime.memoryEnabled()) || content == null || content.isBlank()) {
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

    public void indexOutbound(ConversationMemoryKey key, UUID messageId, String providerMessageId,
                              String content, java.time.Instant occurredAt) {
        var runtime = currentSettings(key.tenantId());
        if (!(runtime == null ? properties.enabled() : runtime.memoryEnabled()) || content == null || content.isBlank()) {
            return;
        }
        var message = new ConversationMessage(messageId, key.conversationId(),
                ConversationMessage.Direction.OUTBOUND, providerMessageId, content, occurredAt);
        publishIndexRequest(key, message);
    }

    public MemoryProjectionUpdate project(ConversationMemoryContext context, String latestUserMessage,
                                          UUID sourceMessageId) {
        return projector.project(context, latestUserMessage, sourceMessageId);
    }

    public MemoryProjectionUpdate project(ConversationMemoryContext context, String latestUserMessage,
                                          UUID sourceMessageId, String assistantResponse) {
        return projector.project(context, latestUserMessage, sourceMessageId, assistantResponse);
    }

    public void invalidate(ConversationMemoryKey key) {
        try {
            memory.invalidate(key);
        } catch (RuntimeException exception) {
            log.warn("Conversation memory invalidation failed: conversationId={} exceptionType={}",
                    key.conversationId(), exception.getClass().getSimpleName());
        }
    }

    public void resetContext(ConversationMemoryKey key, UUID actorId, Instant resetAt) {
        memory.resetContext(key, actorId, resetAt);
    }

    private void publishIndexRequest(ConversationMemoryKey key, ConversationMessage message) {
        try {
            eventPublisher.publishEvent(new com.ford.service.conversations.api.ConversationMemoryIndexRequested(key, message));
        } catch (RuntimeException exception) {
            log.warn("Conversation memory index event publication failed: conversationId={} exceptionType={}",
                    key.conversationId(), exception.getClass().getSimpleName());
        }
    }

    private ConversationRuntimeSettings currentSettings(UUID tenantId) {
        if (runtimeSettings == null) return null;
        try {
            return runtimeSettings.current(tenantId).settings();
        } catch (RuntimeException exception) {
            log.warn("Runtime settings lookup failed; using deployment memory defaults: tenantId={} exceptionType={}",
                    tenantId, exception.getClass().getSimpleName());
            return null;
        }
    }
}
