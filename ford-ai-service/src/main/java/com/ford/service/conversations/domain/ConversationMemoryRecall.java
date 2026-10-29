package com.ford.service.conversations.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ConversationMemoryRecall(
        UUID sourceMessageId,
        ConversationMessage.Direction direction,
        String content,
        Instant occurredAt,
        double similarity) {

    public ConversationMemoryRecall {
        Objects.requireNonNull(sourceMessageId, "sourceMessageId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(occurredAt, "occurredAt");
        content = content == null ? "" : content;
        if (!Double.isFinite(similarity) || similarity < 0 || similarity > 1) {
            throw new IllegalArgumentException("similarity must be between 0 and 1");
        }
    }
}
