package com.ford.service.conversations.api;

import org.springframework.modulith.NamedInterface;

import java.time.Instant;
import java.util.UUID;

@NamedInterface
public record ConversationMemoryRestarted(UUID tenantId, UUID conversationId,
                                          long conversationVersion, Instant contextStartedAt) {
}
