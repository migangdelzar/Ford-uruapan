package com.ford.service.ai.api;

import com.ford.service.leads.domain.LeadJourneyProposal;

/** Structured result from the single assistant/tool conversation turn. */
public record AssistantGeneration(String answer, LeadJourneyProposal journeyProposal, Double modelConfidence) {

    public AssistantGeneration(String answer, LeadJourneyProposal journeyProposal) {
        this(answer, journeyProposal, null);
    }

    public AssistantGeneration {
        if (modelConfidence != null && (!Double.isFinite(modelConfidence)
                || modelConfidence < 0.0 || modelConfidence > 1.0)) {
            throw new IllegalArgumentException("model confidence must be between 0 and 1");
        }
    }
}
