package com.ford.service.conversations.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.application.ConversationRuntimeSettingsCache;
import com.ford.service.conversations.application.ConversationRuntimeSettingsSnapshot;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisConversationRuntimeSettingsCache implements ConversationRuntimeSettingsCache {

    private static final String KEY_PREFIX = "ford:runtime-settings:v1:";
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisConversationRuntimeSettingsCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ConversationRuntimeSettingsSnapshot> load(UUID tenantId) {
        var payload = redis.opsForValue().get(key(tenantId));
        if (payload == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(payload, ConversationRuntimeSettingsSnapshot.class));
        } catch (JsonProcessingException exception) {
            evict(tenantId);
            throw new IllegalStateException("cached runtime settings are invalid", exception);
        }
    }

    @Override
    public void store(ConversationRuntimeSettingsSnapshot settings, Duration ttl) {
        try {
            redis.opsForValue().set(key(settings.tenantId()), objectMapper.writeValueAsString(settings), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize runtime settings cache", exception);
        }
    }

    @Override
    public void evict(UUID tenantId) {
        redis.delete(key(tenantId));
    }

    private static String key(UUID tenantId) {
        return KEY_PREFIX + tenantId;
    }
}
