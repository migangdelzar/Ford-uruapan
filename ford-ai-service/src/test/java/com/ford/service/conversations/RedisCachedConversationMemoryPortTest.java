package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationMemoryCache;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.adapter.out.RedisCachedConversationMemoryPort;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.MemoryUpdateResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCachedConversationMemoryPortTest {

    private final ConversationMemoryPort durable = mock(ConversationMemoryPort.class);
    private final FakeCache cache = new FakeCache();
    private final MemoryProperties properties = new MemoryProperties();
    private RedisCachedConversationMemoryPort port;

    @BeforeEach
    void setUp() {
        port = new RedisCachedConversationMemoryPort(durable, cache, properties, new SimpleMeterRegistry());
    }

    @Test
    void returnsCacheHitWithoutReadingDurableStore() {
        var query = query(null, 20);
        var cached = context(message("one", "one"));
        cache.value = cached;

        assertThat(port.load(query)).isEqualTo(cached);
        verify(durable, never()).load(query);
    }

    @Test
    void loadsDurableMemoryAndStoresItOnCacheMiss() {
        var query = query(null, 20);
        var durableValue = context(message("one", "one"));
        when(durable.load(query)).thenReturn(durableValue);

        assertThat(port.load(query)).isEqualTo(durableValue);
        assertThat(cache.stored).isEqualTo(durableValue);
        verify(durable).load(query);
    }

    @Test
    void fallsBackToDurableStoreWhenCacheReadFails() {
        var query = query(null, 20);
        var durableValue = context(message("one", "one"));
        cache.failure = new IllegalStateException("redis unavailable");
        when(durable.load(query)).thenReturn(durableValue);

        assertThat(port.load(query)).isEqualTo(durableValue);
        verify(durable).load(query);
    }

    @Test
    void evictsAfterDurableMessageWriteAndProjectionUpdate() {
        var key = key();
        var message = message("provider-1", "hello");
        var update = new MemoryProjectionUpdate("summary", List.of(), UUID.randomUUID());
        when(durable.updateProjection(key, update, 0)).thenReturn(new MemoryUpdateResult(true, 1));

        port.recordInbound(key, message);
        port.updateProjection(key, update, 0);

        assertThat(cache.evicted).containsExactly(key, key);
        verify(durable).recordInbound(key, message);
        verify(durable).updateProjection(key, update, 0);
    }

    @Test
    void excludesCurrentProviderMessageAndAppliesRequestedRecentLimitToCacheHits() {
        var current = message("current", "already persisted inbound");
        var older = message("older", "older");
        var newest = message("newest", "newest");
        cache.value = context(older, current, newest);

        var result = port.load(query("current", 1));

        assertThat(result.recentMessages()).extracting(ConversationMessage::providerMessageId)
                .containsExactly("newest");
    }

    @Test
    void preservesDurableResultWhenCacheEvictionFails() {
        var key = key();
        var update = new MemoryProjectionUpdate("summary", List.of(), UUID.randomUUID());
        var expected = new MemoryUpdateResult(true, 1);
        when(durable.updateProjection(key, update, 0)).thenReturn(expected);
        cache.failure = new IllegalStateException("redis unavailable");

        assertThat(port.updateProjection(key, update, 0)).isEqualTo(expected);
    }

    private static MemoryQuery query(String currentProviderMessageId, int recentLimit) {
        return new MemoryQuery(key(), "latest", currentProviderMessageId, recentLimit, 5);
    }

    private static ConversationMemoryContext context(ConversationMessage... messages) {
        return new ConversationMemoryContext(key(), "summary", List.of(messages), List.of(), 4);
    }

    private static ConversationMemoryKey key() {
        return new ConversationMemoryKey(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                Channel.WHATSAPP, "account-1", "participant-1",
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
    }

    private static ConversationMessage message(String providerId, String content) {
        var offset = switch (providerId) {
            case "older" -> 1;
            case "current" -> 2;
            default -> 3;
        };
        return new ConversationMessage(UUID.randomUUID(), key().conversationId(),
                ConversationMessage.Direction.INBOUND, providerId, content,
                Instant.parse("2026-01-01T00:00:0" + offset + "Z"));
    }

    private static final class FakeCache implements ConversationMemoryCache {
        private ConversationMemoryContext value;
        private ConversationMemoryContext stored;
        private RuntimeException failure;
        private final java.util.ArrayList<ConversationMemoryKey> evicted = new java.util.ArrayList<>();

        @Override
        public Optional<ConversationMemoryContext> load(ConversationMemoryKey key) {
            if (failure != null) {
                var exception = failure;
                failure = null;
                throw exception;
            }
            return Optional.ofNullable(value);
        }

        @Override
        public void store(ConversationMemoryContext context, Duration ttl) {
            stored = context;
        }

        @Override
        public void evict(ConversationMemoryKey key) {
            if (failure != null) {
                var exception = failure;
                failure = null;
                throw exception;
            }
            evicted.add(key);
        }
    }
}
