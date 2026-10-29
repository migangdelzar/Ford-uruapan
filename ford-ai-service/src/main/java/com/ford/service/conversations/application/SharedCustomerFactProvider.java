package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.CustomerShoppingFact;

import java.time.Instant;
import java.util.List;

public interface SharedCustomerFactProvider {
    List<CustomerShoppingFact> relevantFacts(ConversationMemoryKey key, String currentMessage,
                                             Instant now, int limit);
}
