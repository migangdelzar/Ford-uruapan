package com.ford.service.conversations.application;

import com.ford.service.conversations.application.port.out.ConversationRuntimeSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class ConversationRuntimeSettingsService {

    private static final Logger log = LoggerFactory.getLogger(ConversationRuntimeSettingsService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final ConversationRuntimeSettingsRepository repository;
    private final ConversationRuntimeSettingsCache cache;
    private final ConversationRuntimeSettings defaults;

    public ConversationRuntimeSettingsService(ConversationRuntimeSettingsRepository repository,
                                              ConversationRuntimeSettingsCache cache,
                                              ConversationRuntimeSettings defaults) {
        this.repository = Objects.requireNonNull(repository);
        this.cache = Objects.requireNonNull(cache);
        this.defaults = Objects.requireNonNull(defaults);
    }

    public ConversationRuntimeSettingsSnapshot current(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        try {
            var cached = cache.load(tenantId);
            if (cached.isPresent()) return cached.get();
        } catch (RuntimeException exception) {
            log.warn("Runtime settings cache read failed; using PostgreSQL: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
        ConversationRuntimeSettingsSnapshot effective;
        try {
            effective = repository.find(tenantId).orElseGet(() ->
                    new ConversationRuntimeSettingsSnapshot(tenantId, 0, defaults, null, null));
        } catch (RuntimeException exception) {
            log.warn("Runtime settings database read failed; using deployment defaults: exceptionType={}",
                    exception.getClass().getSimpleName());
            effective = new ConversationRuntimeSettingsSnapshot(tenantId, 0, defaults, null, null);
        }
        cacheSafely(effective);
        return effective;
    }

    public ConversationRuntimeSettingsSnapshot update(UUID tenantId, UUID actorId, long expectedRevision,
                                                      ConversationRuntimeSettings settings) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(actorId, "actorId is required");
        Objects.requireNonNull(settings, "settings are required");
        if (expectedRevision < 0) throw new IllegalArgumentException("revision must not be negative");
        var updated = repository.update(tenantId, actorId, expectedRevision, settings);
        try {
            cache.store(updated, CACHE_TTL);
        } catch (RuntimeException exception) {
            log.warn("Runtime settings cache refresh failed after durable update: exceptionType={}",
                    exception.getClass().getSimpleName());
            evictSafely(tenantId);
        }
        return updated;
    }

    private void cacheSafely(ConversationRuntimeSettingsSnapshot snapshot) {
        try {
            cache.store(snapshot, CACHE_TTL);
        } catch (RuntimeException exception) {
            log.warn("Runtime settings cache population failed: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void evictSafely(UUID tenantId) {
        try {
            cache.evict(tenantId);
        } catch (RuntimeException exception) {
            log.warn("Runtime settings cache eviction failed: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
