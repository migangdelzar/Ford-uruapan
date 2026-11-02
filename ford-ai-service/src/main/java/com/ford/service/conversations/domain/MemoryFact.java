package com.ford.service.conversations.domain;

import java.util.Objects;
import java.util.UUID;

public record MemoryFact(String kind, String content, UUID sourceMessageId) {

    public MemoryFact {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("memory fact kind is required");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("memory fact content is required");
        }
    }
}
