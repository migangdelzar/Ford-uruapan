package com.ford.service.ai.api;

import java.util.UUID;

public record AssistantRequest(
        UUID tenantId,
        UUID conversationId,
        String message,
        String conversationState,
        String memoryContext,
        boolean containsSensitiveData) {

    public AssistantRequest(UUID tenantId, UUID conversationId, String message,
                             String conversationState, boolean containsSensitiveData) {
        this(tenantId, conversationId, message, conversationState, null, containsSensitiveData);
    }
}
