package com.ford.service.ai.api;

import com.ford.service.ai.application.ConversationDecision;

import java.util.UUID;

public record AssistantRequest(
        UUID tenantId,
        UUID conversationId,
        String message,
        String conversationState,
        String memoryContext,
        boolean containsSensitiveData,
        ConversationDecision decision,
        String leadJourneyContext) {

    public AssistantRequest {
        decision = decision == null ? ConversationDecision.deterministicDefault() : decision;
    }

    public AssistantRequest(UUID tenantId, UUID conversationId, String message,
                             String conversationState, boolean containsSensitiveData) {
        this(tenantId, conversationId, message, conversationState, null, containsSensitiveData,
                ConversationDecision.deterministicDefault(), null);
    }

    public AssistantRequest(UUID tenantId, UUID conversationId, String message,
                             String conversationState, String memoryContext,
                             boolean containsSensitiveData) {
        this(tenantId, conversationId, message, conversationState, memoryContext, containsSensitiveData,
                ConversationDecision.deterministicDefault(), null);
    }

    public AssistantRequest(UUID tenantId, UUID conversationId, String message, String conversationState,
                            String memoryContext, boolean containsSensitiveData, ConversationDecision decision) {
        this(tenantId, conversationId, message, conversationState, memoryContext, containsSensitiveData,
                decision, null);
    }
}
