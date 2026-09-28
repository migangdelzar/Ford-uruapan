package com.ford.service.ai.api;

import com.ford.service.leads.domain.LeadJourneyProposal;

/** Structured result from the single assistant/tool conversation turn. */
public record AssistantGeneration(String answer, LeadJourneyProposal journeyProposal) {
}
