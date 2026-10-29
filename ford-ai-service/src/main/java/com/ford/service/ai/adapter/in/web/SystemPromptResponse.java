package com.ford.service.ai.adapter.in.web;

import java.time.Instant;

public record SystemPromptResponse(String content, long version, String updatedBy, Instant updatedAt) {
}
