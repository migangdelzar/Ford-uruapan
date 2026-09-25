package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.out.RedisConversationMemoryCache;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisConversationMemoryCacheTest {

    @Test
    void storesAndLoadsSerializedContextWithIdentityScopedKeyAndTtl() {
        var redis = mock(StringRedisTemplate.class);
        var hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(any())).thenReturn(Map.of());
        var properties = new MemoryProperties();
        var cache = new RedisConversationMemoryCache(redis, new ObjectMapper(), properties,
                new SimpleMeterRegistry());
        var context = new ConversationMemoryContext(key(), "summary", List.of(), List.of(), 4);

        cache.store(context, Duration.ofHours(24));

        verify(redis).execute(any(),
                eq(List.of("ford:memory:v1:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:WHATSAPP:account-1:participant-1:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")),
                eq("memoryVersion"), eq("payload"), any(), eq("4"),
                eq(Long.toString(Duration.ofHours(24).toMillis())));
    }

    @Test
    void treatsMissingOrInvalidPayloadAsCacheMiss() {
        var redis = mock(StringRedisTemplate.class);
        var hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(any())).thenReturn(Map.of("memoryVersion", "4", "payload", "not-json"));
        var cache = new RedisConversationMemoryCache(redis, new ObjectMapper(), new MemoryProperties(),
                new SimpleMeterRegistry());

        assertThat(cache.load(key())).isEmpty();
    }

    private static ConversationMemoryKey key() {
        return new ConversationMemoryKey(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                Channel.WHATSAPP, "account-1", "participant-1",
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
    }
}
