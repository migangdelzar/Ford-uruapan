package com.ford.service.ai.application;

import java.util.UUID;
import java.util.List;

public record AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId,
                                 String memoryContext, ConversationDecision decision, String leadJourneyContext,
                                 List<String> recentAssistantReplies, String repairInstruction) {

    public AiExecutionContext {
        decision = decision == null ? ConversationDecision.deterministicDefault() : decision;
        recentAssistantReplies = List.copyOf(recentAssistantReplies == null ? List.of() : recentAssistantReplies);
    }

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId,
                              String memoryContext, ConversationDecision decision, String leadJourneyContext) {
        this(tenantId, containsSensitiveData, conversationId, memoryContext, decision, leadJourneyContext, List.of());
    }

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId,
                              String memoryContext, ConversationDecision decision, String leadJourneyContext,
                              List<String> recentAssistantReplies) {
        this(tenantId, containsSensitiveData, conversationId, memoryContext, decision, leadJourneyContext,
                recentAssistantReplies, null);
    }

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData) {
        this(tenantId, containsSensitiveData, null, null, null, null, List.of());
    }

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId) {
        this(tenantId, containsSensitiveData, conversationId, null, null, null, List.of());
    }

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId,
                              String memoryContext) {
        this(tenantId, containsSensitiveData, conversationId, memoryContext, null, null, List.of());
    }

    public AiExecutionContext(UUID tenantId, boolean containsSensitiveData, UUID conversationId,
                              String memoryContext, ConversationDecision decision) {
        this(tenantId, containsSensitiveData, conversationId, memoryContext, decision, null, List.of());
    }
}
