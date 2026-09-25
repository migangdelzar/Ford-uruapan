package com.ford.service.conversations.application;

import com.ford.service.ai.application.ConversationDecision;

import java.util.UUID;

public interface ConversationDecisionRepository {
    void save(UUID conversationId, String providerMessageId, ConversationDecision decision);
}
