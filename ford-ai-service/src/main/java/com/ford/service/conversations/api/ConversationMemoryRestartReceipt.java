package com.ford.service.conversations.api;

import java.time.Instant;
import java.util.UUID;

public record ConversationMemoryRestartReceipt(UUID conversationId, long conversationVersion,
                                               Instant contextStartedAt) {
}
