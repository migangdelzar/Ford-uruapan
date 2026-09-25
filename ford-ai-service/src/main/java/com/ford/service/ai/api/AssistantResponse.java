package com.ford.service.ai.api;

import com.ford.service.leads.domain.LeadJourneyProposal;

import java.util.List;

public record AssistantResponse(
        String content,
        boolean handoffRequired,
        String provider,
        List<String> sourceDocumentIds,
        LeadJourneyProposal journeyProposal) {
    public AssistantResponse {
        sourceDocumentIds = List.copyOf(sourceDocumentIds == null ? List.of() : sourceDocumentIds);
    }

    public AssistantResponse(String content, boolean handoffRequired, String provider,
                             List<String> sourceDocumentIds) {
        this(content, handoffRequired, provider, sourceDocumentIds, null);
    }
}
