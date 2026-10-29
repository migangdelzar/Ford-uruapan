package com.ford.service.ai.api;

import com.ford.service.ai.application.Intent;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.leads.domain.LeadJourneyProposal;

import java.util.List;
import java.util.Objects;

public record AssistantTurn(
        String text,
        Intent intent,
        boolean requestsHuman,
        List<String> sourceIds,
        String provider,
        String modelVersion,
        ConversationDecision decision,
        LeadJourneyProposal journeyProposal,
        List<DecisionTraceEvent> decisionTrace,
        ResponseKind responseKind) {

    public AssistantTurn {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Assistant turn text is required");
        }
        Objects.requireNonNull(intent, "intent is required");
        sourceIds = List.copyOf(sourceIds == null ? List.of() : sourceIds);
        provider = provider == null || provider.isBlank() ? "unknown" : provider;
        modelVersion = modelVersion == null || modelVersion.isBlank() ? "unknown" : modelVersion;
        decision = decision == null ? ConversationDecision.deterministicDefault() : decision;
        decisionTrace = List.copyOf(decisionTrace == null ? List.of() : decisionTrace);
        responseKind = responseKind == null ? ResponseKind.ANSWER : responseKind;
    }

    public AssistantTurn(String text, Intent intent, boolean requestsHuman, List<String> sourceIds,
                         String provider, String modelVersion, ConversationDecision decision,
                         LeadJourneyProposal journeyProposal, List<DecisionTraceEvent> decisionTrace) {
        this(text, intent, requestsHuman, sourceIds, provider, modelVersion, decision, journeyProposal,
                decisionTrace, ResponseKind.ANSWER);
    }

    public AssistantTurn(String text, Intent intent, boolean requestsHuman, List<String> sourceIds,
                         String provider, String modelVersion, ConversationDecision decision,
                         LeadJourneyProposal journeyProposal) {
        this(text, intent, requestsHuman, sourceIds, provider, modelVersion, decision, journeyProposal,
                List.of(), ResponseKind.ANSWER);
    }

    public AssistantTurn(String text, Intent intent, boolean requestsHuman,
                         List<String> sourceIds, String provider, String modelVersion) {
        this(text, intent, requestsHuman, sourceIds, provider, modelVersion,
                ConversationDecision.deterministicDefault(), null);
    }

    public AssistantTurn(String text, Intent intent, boolean requestsHuman,
                         List<String> sourceIds, String provider, String modelVersion,
                         ConversationDecision decision) {
        this(text, intent, requestsHuman, sourceIds, provider, modelVersion, decision, null);
    }

    public ResponseKind responseKind() { return responseKind; }
}
