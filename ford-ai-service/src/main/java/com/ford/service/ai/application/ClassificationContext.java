package com.ford.service.ai.application;

import java.util.UUID;

public record ClassificationContext(
        UUID tenantId,
        UUID conversationId,
        String message,
        String memoryContext,
        String conversationState,
        boolean containsSensitiveData) {
}
