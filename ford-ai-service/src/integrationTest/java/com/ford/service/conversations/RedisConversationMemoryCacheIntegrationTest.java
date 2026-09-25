package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.out.RedisConversationMemoryCache;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisConversationMemoryCacheIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Test
    void roundTripsBoundedMemoryWithPositiveTtlAndEviction() throws InterruptedException {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        var redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        var properties = new MemoryProperties();
        properties.setCacheTtl(Duration.ofSeconds(5));
        var cache = new RedisConversationMemoryCache(redis, new ObjectMapper().findAndRegisterModules(),
                properties, new SimpleMeterRegistry());
        var context = new ConversationMemoryContext(key(), "summary",
                List.of(new ConversationMessage(UUID.randomUUID(), key().conversationId(),
                        ConversationMessage.Direction.INBOUND, "provider-message-1", "Hello",
                        Instant.parse("2026-01-01T00:00:00Z"))),
                List.of(), 4);
        var redisKey = "ford:memory:v1:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:WHATSAPP:account%2Fone:participant%3Aone:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

        try {
            cache.store(context, properties.cacheTtl());

            assertThat(cache.load(context.key())).contains(context);
            assertThat(redis.getExpire(redisKey)).isPositive();

            Thread.sleep(300);
            var beforeRefresh = redis.getExpire(redisKey, TimeUnit.MILLISECONDS);
            assertThat(cache.load(context.key())).contains(context);
            assertThat(redis.getExpire(redisKey, TimeUnit.MILLISECONDS)).isGreaterThan(beforeRefresh);

            cache.store(new ConversationMemoryContext(context.key(), "stale", List.of(), List.of(), 3),
                    properties.cacheTtl());
            assertThat(cache.load(context.key())).contains(context);

            cache.evict(context.key());

            assertThat(cache.load(context.key())).isEmpty();
        } finally {
            factory.destroy();
        }
    }

    private static ConversationMemoryKey key() {
        return new ConversationMemoryKey(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                Channel.WHATSAPP, "account/one", "participant:one",
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
    }
}
