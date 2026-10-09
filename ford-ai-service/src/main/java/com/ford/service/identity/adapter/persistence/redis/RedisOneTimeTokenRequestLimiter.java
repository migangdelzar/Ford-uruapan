package com.ford.service.identity.adapter.persistence.redis;

import com.ford.service.identity.application.OneTimeTokenRequestLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Component
public class RedisOneTimeTokenRequestLimiter implements OneTimeTokenRequestLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private final StringRedisTemplate redis;

    public RedisOneTimeTokenRequestLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean allow(String normalizedEmail) {
        var key = "ford:auth:ott:request:" + sha256(normalizedEmail);
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", WINDOW));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
