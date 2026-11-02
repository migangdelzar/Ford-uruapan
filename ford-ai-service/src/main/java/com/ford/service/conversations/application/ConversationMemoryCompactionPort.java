package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryFact;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface ConversationMemoryCompactionPort {

    String compact(UUID tenantId, UUID conversationId, String previousSummary,
                   List<ConversationMessage> expiredMessages, int maxChars);

    default String rebase(UUID tenantId, UUID conversationId, List<ConversationMessage> sourceMessages,
                          List<MemoryFact> currentFacts, int maxChars) {
        return compact(tenantId, conversationId, "", sourceMessages, maxChars);
    }

    static ConversationMemoryCompactionPort unavailable() {
        return (tenantId, conversationId, previousSummary, expiredMessages, maxChars) -> previousSummary;
    }
}
