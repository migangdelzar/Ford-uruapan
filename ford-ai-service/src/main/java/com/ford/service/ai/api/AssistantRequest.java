package com.ford.service.ai.api;

import java.util.UUID;

public record AssistantRequest(
        UUID tenantId,
        UUID conversationId,
        String message,
        String conversationState,
        boolean containsSensitiveData) {
}
