package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.adapter.out.RedisConversationRuntimeSettingsCache;
import com.ford.service.conversations.application.ConversationRuntimeSettings;
import com.ford.service.conversations.application.ConversationRuntimeSettingsSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisConversationRuntimeSettingsCacheIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void sharesVersionedSettingsAcrossCacheReadsAndEvictsOnInvalidation() {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        var redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        var cache = new RedisConversationRuntimeSettingsCache(redis, new ObjectMapper().findAndRegisterModules());
        var tenantId = UUID.randomUUID();
        var snapshot = new ConversationRuntimeSettingsSnapshot(tenantId, 4,
                new ConversationRuntimeSettings(true, false, 8), Instant.now(), UUID.randomUUID());

        try {
            cache.store(snapshot, Duration.ofSeconds(20));

            assertThat(cache.load(tenantId)).contains(snapshot);
            assertThat(redis.getExpire("ford:runtime-settings:v1:" + tenantId)).isPositive();
            cache.evict(tenantId);
            assertThat(cache.load(tenantId)).isEmpty();
        } finally {
            factory.destroy();
        }
    }
}
