package com.ford.service.ai.api;

import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.DecisionTraceCollector;

import java.util.UUID;
import java.util.List;

public record AssistantRequest(
        UUID tenantId,
        UUID conversationId,
        String message,
        String conversationState,
        String memoryContext,
        boolean containsSensitiveData,
        ConversationDecision decision,
        String leadJourneyContext,
        List<String> recentAssistantReplies,
        String repairInstruction,
        DecisionTraceCollector traceCollector) {

    public AssistantRequest {
        decision = decision == null ? ConversationDecision.deterministicDefault() : decision;
        recentAssistantReplies = List.copyOf(recentAssistantReplies == null ? List.of() : recentAssistantReplies);
    }

    public AssistantRequest(UUID tenantId, UUID conversationId, String message, String conversationState,
                            String memoryContext, boolean containsSensitiveData, ConversationDecision decision,
                            String leadJourneyContext, List<String> recentAssistantReplies, String repairInstruction) {
        this(tenantId, conversationId, message, conversationState, memoryContext, containsSensitiveData,
                decision, leadJourneyContext, recentAssistantReplies, repairInstruction, null);
    }

    public AssistantRequest(UUID tenantId, UUID conversationId, String message,
                            String conversationState, String memoryContext, boolean containsSensitiveData,
                            ConversationDecision decision, String leadJourneyContext) {
        this(tenantId, conversationId, message, conversationState, memoryContext, containsSensitiveData,
                decision, leadJourneyContext, List.of(), null);
    }

    public AssistantRequest(UUID tenantId, UUID conversationId, String message,
                            String conversationState, String memoryContext, boolean containsSensitiveData,
                            ConversationDecision decision, String leadJourneyContext,
                            List<String> recentAssistantReplies) {
        this(tenantId, conversationId, message, conversationState, memoryContext, containsSensitiveData,
                decision, leadJourneyContext, recentAssistantReplies, null);
    }

    public AssistantRequest(UUID tenantId, UUID conversationId, String message,
                             String conversationState, boolean containsSensitiveData) {
        this(tenantId, conversationId, message, conversationState, null, containsSensitiveData,
                ConversationDecision.deterministicDefault(), null, List.of(), null);
    }

    public AssistantRequest(UUID tenantId, UUID conversationId, String message,
                             String conversationState, String memoryContext,
                             boolean containsSensitiveData) {
        this(tenantId, conversationId, message, conversationState, memoryContext, containsSensitiveData,
                ConversationDecision.deterministicDefault(), null, List.of(), null);
    }

    public AssistantRequest(UUID tenantId, UUID conversationId, String message, String conversationState,
                            String memoryContext, boolean containsSensitiveData, ConversationDecision decision) {
        this(tenantId, conversationId, message, conversationState, memoryContext, containsSensitiveData,
                decision, null, List.of(), null);
    }

    public AssistantRequest withRepairInstruction(String instruction) {
        return new AssistantRequest(tenantId, conversationId, message, conversationState, memoryContext,
                containsSensitiveData, decision, leadJourneyContext, recentAssistantReplies, instruction,
                traceCollector);
    }
}
