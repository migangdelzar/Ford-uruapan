package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.application.ConversationRuntimeSettings;
import com.ford.service.conversations.application.ConversationRuntimeSettingsSnapshot;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRuntimeSettingsRepository {
    Optional<ConversationRuntimeSettingsSnapshot> find(UUID tenantId);

    ConversationRuntimeSettingsSnapshot update(UUID tenantId, UUID actorId, long expectedRevision,
                                                ConversationRuntimeSettings settings);
}
