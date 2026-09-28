package com.ford.service.ai.application;

import java.util.UUID;

public record AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId,
                                 String memoryContext, ConversationDecision decision, String leadJourneyContext) {

    public AiExecutionContext {
        decision = decision == null ? ConversationDecision.deterministicDefault() : decision;
    }

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData) {
        this(tenantId, containsSensitiveData, null, null, null, null);
    }

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId) {
        this(tenantId, containsSensitiveData, conversationId, null, null, null);
    }

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId,
                              String memoryContext) {
        this(tenantId, containsSensitiveData, conversationId, memoryContext, null, null);
    }

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId,
                              String memoryContext, ConversationDecision decision) {
        this(tenantId, containsSensitiveData, conversationId, memoryContext, decision, null);
    }
}
