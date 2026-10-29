package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.domain.ConversationMemoryKey;

import java.util.Optional;
import java.util.UUID;

public interface ConversationMemoryKeyRepository {
    Optional<ConversationMemoryKey> find(UUID tenantId, UUID conversationId);
}
