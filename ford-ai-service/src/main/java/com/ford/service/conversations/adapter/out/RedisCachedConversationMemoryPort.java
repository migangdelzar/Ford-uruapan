package com.ford.service.conversations.adapter.out;

import com.ford.service.conversations.application.ConversationMemoryCache;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.MemoryUpdateResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.time.Instant;

public final class RedisCachedConversationMemoryPort implements ConversationMemoryPort {

    private static final Logger log = LoggerFactory.getLogger(RedisCachedConversationMemoryPort.class);

    private final ConversationMemoryPort durable;
    private final ConversationMemoryCache cache;
    private final MemoryProperties properties;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter cacheFailures;
    private final Counter durableFallbacks;
    private final Timer cacheLookupLatency;

    public RedisCachedConversationMemoryPort(ConversationMemoryPort durable,
                                             ConversationMemoryCache cache,
                                             MemoryProperties properties,
                                             MeterRegistry meterRegistry) {
        this.durable = Objects.requireNonNull(durable);
        this.cache = Objects.requireNonNull(cache);
        this.properties = Objects.requireNonNull(properties);
        this.cacheHits = counter(meterRegistry, "hit");
        this.cacheMisses = counter(meterRegistry, "miss");
        this.cacheFailures = counter(meterRegistry, "failure");
        this.durableFallbacks = Counter.builder("ford.memory.cache.fallback")
                .description("Conversation-memory loads that required the durable store")
                .register(meterRegistry);
        this.cacheLookupLatency = Timer.builder("ford.memory.cache.lookup")
                .description("Conversation hot-memory cache lookup latency")
                .register(meterRegistry);
    }

    @Override
    public ConversationMemoryContext load(MemoryQuery query) {
        var sample = Timer.start();
        try {
            var cached = cache.load(query.key());
            if (cached.isPresent()) {
                var updatedAt = cached.get().updatedAt();
                if (updatedAt == null || updatedAt.isAfter(Instant.now().minus(properties.workingMemoryTtl()))) {
                    cacheHits.increment();
                    return bound(cached.get(), query);
                }
                evictSafely(query.key());
            }
            cacheMisses.increment();
            durableFallbacks.increment();
        } catch (RuntimeException exception) {
            cacheFailures.increment();
            durableFallbacks.increment();
            log.warn("Conversation memory cache lookup failed: exceptionType={}",
                    exception.getClass().getSimpleName());
        } finally {
            sample.stop(cacheLookupLatency);
        }

        var durableContext = durable.load(query);
        try {
            cache.store(durableContext, properties.cacheTtl());
        } catch (RuntimeException exception) {
            cacheFailures.increment();
            log.warn("Conversation memory cache population failed: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
        return durableContext;
    }

    @Override
    public void recordInbound(ConversationMemoryKey key, ConversationMessage message) {
        durable.recordInbound(key, message);
        evictSafely(key);
    }

    @Override
    public List<ConversationMessage> loadTranscriptForRebase(ConversationMemoryKey key, Instant contextStartedAt,
                                                            int keepRecentCount, int maxRebaseMessages) {
        return durable.loadTranscriptForRebase(key, contextStartedAt, keepRecentCount, maxRebaseMessages);
    }

    @Override
    public void invalidate(ConversationMemoryKey key) {
        evictSafely(key);
    }

    @Override
    public void resetContext(ConversationMemoryKey key, java.util.UUID actorId, Instant resetAt) {
        durable.resetContext(key, actorId, resetAt);
        try {
            cache.evict(key);
        } catch (RuntimeException exception) {
            cacheFailures.increment();
            log.error("Conversation memory restart cache eviction failed: conversationId={} exceptionType={}",
                    key.conversationId(), exception.getClass().getSimpleName());
            throw new IllegalStateException("conversation memory cache unavailable; restart was not committed", exception);
        }
    }

    @Override
    public MemoryUpdateResult updateProjection(ConversationMemoryKey key,
                                                MemoryProjectionUpdate update,
                                                long expectedVersion) {
        var result = durable.updateProjection(key, update, expectedVersion);
        evictSafely(key);
        return result;
    }

    private void evictSafely(ConversationMemoryKey key) {
        // The cache implementation is fail-open too, but this boundary also protects
        // alternate cache implementations from changing durable-memory semantics.
        try {
            cache.evict(key);
        } catch (RuntimeException exception) {
            cacheFailures.increment();
            log.warn("Conversation memory cache invalidation failed: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private static ConversationMemoryContext bound(ConversationMemoryContext context, MemoryQuery query) {
        var messages = context.recentMessages().stream()
                .filter(message -> !Objects.equals(message.providerMessageId(), query.currentProviderMessageId()))
                .toList();
        var limit = query.recentMessageLimit();
        var fromIndex = Math.max(0, messages.size() - limit);
        var boundedMessages = List.copyOf(messages.subList(fromIndex, messages.size()));
        return new ConversationMemoryContext(context.key(), context.summary(), boundedMessages,
                context.durableFacts(), context.memoryVersion(), context.updatedAt(),
                context.recalledTurns(), context.contextStartedAt());
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("ford.memory.port.cache")
                .description("Conversation-memory port cache outcomes")
                .tag("outcome", outcome)
                .register(registry);
    }
}
