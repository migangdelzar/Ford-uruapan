package com.ford.service.leads.domain;

import java.util.List;

public record LeadJourneyProposal(LeadJourneyTopic currentTopic,
                                 List<ProposedLeadJourneyFact> facts,
                                 String pendingQuestionKey) {
    public LeadJourneyProposal {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
