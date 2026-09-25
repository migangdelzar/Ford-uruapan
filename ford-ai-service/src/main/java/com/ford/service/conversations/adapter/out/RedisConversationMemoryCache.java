package com.ford.service.conversations.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.application.ConversationMemoryCache;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RedisConversationMemoryCache implements ConversationMemoryCache {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationMemoryCache.class);
    private static final String KEY_PREFIX = "ford:memory:v1:";
    private static final String VERSION_FIELD = "memoryVersion";
    private static final String PAYLOAD_FIELD = "payload";
    private static final RedisScript<Long> STORE_IF_NEWER = new DefaultRedisScript<>("""
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            local currentNumber = current and tonumber(current)
            local incomingNumber = tonumber(ARGV[4])
            if currentNumber and incomingNumber and currentNumber > incomingNumber then
              return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[4], ARGV[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration cacheTtl;
    private final Counter loads;
    private final Counter hits;
    private final Counter misses;
    private final Counter stales;
    private final Counter stores;
    private final Counter evictions;
    private final Counter failures;

    public RedisConversationMemoryCache(StringRedisTemplate redis,
                                        ObjectMapper objectMapper,
                                        MemoryProperties properties,
                                        MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.cacheTtl = properties.cacheTtl();
        this.loads = counter(meterRegistry, "load");
        this.hits = counter(meterRegistry, "hit");
        this.misses = counter(meterRegistry, "miss");
        this.stales = counter(meterRegistry, "stale");
        this.stores = counter(meterRegistry, "store");
        this.evictions = counter(meterRegistry, "evict");
        this.failures = counter(meterRegistry, "failure");
    }

    @Override
    public Optional<ConversationMemoryContext> load(ConversationMemoryKey key) {
        loads.increment();
        var redisKey = redisKey(key);
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(redisKey);
            var versionValue = entries.get(VERSION_FIELD);
            var payloadValue = entries.get(PAYLOAD_FIELD);
            if (versionValue == null || payloadValue == null) {
                misses.increment();
                return Optional.empty();
            }

            var expectedVersion = Long.parseLong(versionValue.toString());
            var context = objectMapper.readValue(payloadValue.toString(), ConversationMemoryContext.class);
            if (context.memoryVersion() != expectedVersion) {
                stales.increment();
                misses.increment();
                return Optional.empty();
            }

            refreshTtl(redisKey);
            hits.increment();
            return Optional.of(context);
        } catch (JsonProcessingException | RuntimeException exception) {
            failures.increment();
            misses.increment();
            log.warn("Conversation memory cache read failed: exceptionType={}",
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void store(ConversationMemoryContext context, Duration ttl) {
        var redisKey = redisKey(context.key());
        try {
            var payload = objectMapper.writeValueAsString(context);
            var stored = redis.execute(STORE_IF_NEWER, List.of(redisKey),
                    VERSION_FIELD,
                    PAYLOAD_FIELD,
                    payload,
                    Long.toString(context.memoryVersion()),
                    Long.toString(ttl.toMillis()));
            if (Long.valueOf(1L).equals(stored)) {
                stores.increment();
            } else {
                stales.increment();
            }
        } catch (JsonProcessingException | RuntimeException exception) {
            failures.increment();
            log.warn("Conversation memory cache write failed: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    @Override
    public void evict(ConversationMemoryKey key) {
        try {
            redis.delete(redisKey(key));
            evictions.increment();
        } catch (RuntimeException exception) {
            failures.increment();
            log.warn("Conversation memory cache eviction failed: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    static String redisKey(ConversationMemoryKey key) {
        return String.join(":",
                KEY_PREFIX.substring(0, KEY_PREFIX.length() - 1),
                key.tenantId().toString(),
                key.channel().name(),
                encode(key.providerAccountId()),
                encode(key.participantId()),
                key.conversationId().toString());
    }

    private HashOperations<String, Object, Object> hashOperations() {
        return redis.opsForHash();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void refreshTtl(String redisKey) {
        try {
            redis.expire(redisKey, cacheTtl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException exception) {
            failures.increment();
            log.warn("Conversation memory cache TTL refresh failed: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("ford.memory.cache." + outcome)
                .description("Conversation hot-memory cache operations")
                .register(registry);
    }
}
