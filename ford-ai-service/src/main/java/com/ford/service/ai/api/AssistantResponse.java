package com.ford.service.ai.api;

import com.ford.service.leads.domain.LeadJourneyProposal;

import java.util.List;

public record AssistantResponse(
        String content,
        boolean handoffRequired,
        String provider,
        List<String> sourceDocumentIds,
        LeadJourneyProposal journeyProposal,
        Double modelConfidence,
        ResponseEvidenceStatus evidenceStatus,
        ResponseKind responseKind) {

    public AssistantResponse {
        sourceDocumentIds = List.copyOf(sourceDocumentIds == null ? List.of() : sourceDocumentIds);
        evidenceStatus = evidenceStatus == null ? ResponseEvidenceStatus.NOT_REQUIRED : evidenceStatus;
        responseKind = responseKind == null ? ResponseKind.ANSWER : responseKind;
        if (modelConfidence != null && (!Double.isFinite(modelConfidence)
                || modelConfidence < 0.0 || modelConfidence > 1.0)) {
            throw new IllegalArgumentException("model confidence must be between 0 and 1");
        }
    }

    public AssistantResponse(String content, boolean handoffRequired, String provider,
                             List<String> sourceDocumentIds, LeadJourneyProposal journeyProposal,
                             Double modelConfidence, ResponseEvidenceStatus evidenceStatus) {
        this(content, handoffRequired, provider, sourceDocumentIds, journeyProposal, modelConfidence,
                evidenceStatus, ResponseKind.ANSWER);
    }

    public AssistantResponse(String content, boolean handoffRequired, String provider,
                             List<String> sourceDocumentIds, LeadJourneyProposal journeyProposal) {
        this(content, handoffRequired, provider, sourceDocumentIds, journeyProposal,
                null, ResponseEvidenceStatus.NOT_REQUIRED, ResponseKind.ANSWER);
    }

    public AssistantResponse(String content, boolean handoffRequired, String provider,
                             List<String> sourceDocumentIds) {
        this(content, handoffRequired, provider, sourceDocumentIds, null,
                null, ResponseEvidenceStatus.NOT_REQUIRED, ResponseKind.ANSWER);
    }

    public ResponseKind responseKind() { return responseKind; }
}
