package com.ford.service.conversations.api;

import org.springframework.modulith.NamedInterface;

import java.time.Instant;
import java.util.UUID;

@NamedInterface
public record ConversationMemoryRestarted(UUID tenantId, UUID conversationId,
                                          long conversationVersion, Instant contextStartedAt,
                                          String actorId, ConversationMemoryRestartRequest.RestartReason reason) {
    public ConversationMemoryRestarted(UUID tenantId, UUID conversationId,
                                       long conversationVersion, Instant contextStartedAt) {
        this(tenantId, conversationId, conversationVersion, contextStartedAt, "legacy",
                ConversationMemoryRestartRequest.RestartReason.LEGACY_CLIENT_UNSPECIFIED);
    }
}
