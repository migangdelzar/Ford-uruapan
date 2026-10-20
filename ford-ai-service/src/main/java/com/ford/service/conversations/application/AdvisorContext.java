package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.CustomerShoppingFact;
import com.ford.service.leads.domain.LeadJourneySnapshot;

import java.util.List;

public record AdvisorContext(String currentMessage, ConversationMemoryContext threadMemory,
                             List<CustomerShoppingFact> sharedCustomerFacts,
                             LeadJourneySnapshot leadJourney) {
    public AdvisorContext {
        currentMessage = currentMessage == null ? "" : currentMessage;
        sharedCustomerFacts = List.copyOf(sharedCustomerFacts == null ? List.of()
                : sharedCustomerFacts.stream().limit(3).toList());
    }
}
