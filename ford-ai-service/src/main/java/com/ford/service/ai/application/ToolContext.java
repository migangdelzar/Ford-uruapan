package com.ford.service.ai.application;

import java.util.UUID;

public record ToolContext(UUID tenantId, UUID conversationId) {
}
