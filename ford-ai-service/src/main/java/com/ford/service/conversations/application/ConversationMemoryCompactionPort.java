package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMessage;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface ConversationMemoryCompactionPort {

    String compact(UUID tenantId, UUID conversationId, String previousSummary,
                   List<ConversationMessage> expiredMessages, int maxChars);

    static ConversationMemoryCompactionPort unavailable() {
        return (tenantId, conversationId, previousSummary, expiredMessages, maxChars) -> previousSummary;
    }
}
