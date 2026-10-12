package com.ford.service.conversations.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRuntimeSettingsCache {
    Optional<ConversationRuntimeSettingsSnapshot> load(UUID tenantId);
    void store(ConversationRuntimeSettingsSnapshot settings, Duration ttl);
    void evict(UUID tenantId);
}
