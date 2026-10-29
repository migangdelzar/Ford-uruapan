package com.ford.service.ai.application;

import java.time.Instant;

public record SystemPromptSnapshot(String content, long version, String updatedBy, Instant updatedAt) {
    public SystemPromptSnapshot {
        if (content == null || content.isBlank() || version < 0 || updatedBy == null || updatedAt == null) {
            throw new IllegalArgumentException("valid system prompt metadata is required");
        }
    }
}
