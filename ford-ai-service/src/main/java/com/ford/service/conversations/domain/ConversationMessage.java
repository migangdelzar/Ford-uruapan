package com.ford.service.conversations.domain;

import java.time.Instant;
import java.util.UUID;

public record ConversationMessage(
        UUID id,
        UUID conversationId,
        Direction direction,
        String providerMessageId,
        String content,
        Instant occurredAt) {

    public enum Direction { INBOUND, OUTBOUND }
}
