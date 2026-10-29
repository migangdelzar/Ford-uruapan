package com.ford.service.conversations.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Operator-facing view of an unsent shadow draft. Deliberately excludes
 * provider payloads and any delivery identity.
 */
public record ShadowDraftView(UUID id, UUID conversationId, Instant createdAt, Instant expiresAt,
                                 String reviewStatus, String text) {
}
