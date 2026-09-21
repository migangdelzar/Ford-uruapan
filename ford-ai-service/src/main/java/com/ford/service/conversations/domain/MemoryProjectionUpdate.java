package com.ford.service.conversations.domain;

import java.util.List;
import java.util.UUID;

public record MemoryProjectionUpdate(
        String summary,
        List<MemoryFact> durableFacts,
        UUID sourceMessageId) {

    public MemoryProjectionUpdate {
        summary = summary == null ? "" : summary;
        durableFacts = List.copyOf(durableFacts == null ? List.of() : durableFacts);
    }
}
