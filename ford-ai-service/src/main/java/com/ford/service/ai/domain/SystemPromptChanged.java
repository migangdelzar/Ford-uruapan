package com.ford.service.ai.domain;

import java.time.Instant;

public record SystemPromptChanged(long version, String updatedBy, Instant changedAt) {
}
