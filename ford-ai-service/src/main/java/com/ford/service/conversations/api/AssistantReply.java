package com.ford.service.conversations.api;

import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.leads.domain.LeadJourneyProposal;

import java.util.List;

public record AssistantReply(String text, boolean handoffRequired, ConversationDecision decision,
                             LeadJourneyProposal journeyProposal, List<DecisionTraceEvent> decisionTrace) {
    public AssistantReply {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Assistant reply text is required");
        }
        decision = decision == null ? ConversationDecision.deterministicDefault() : decision;
        decisionTrace = List.copyOf(decisionTrace == null ? List.of() : decisionTrace);
    }

    public AssistantReply(String text, boolean handoffRequired, ConversationDecision decision,
                          LeadJourneyProposal journeyProposal) {
        this(text, handoffRequired, decision, journeyProposal, List.of());
    }

    public AssistantReply(String text, boolean handoffRequired) {
        this(text, handoffRequired, ConversationDecision.deterministicDefault(), null);
    }

    public AssistantReply(String text, boolean handoffRequired, ConversationDecision decision) {
        this(text, handoffRequired, decision, null);
    }
}
