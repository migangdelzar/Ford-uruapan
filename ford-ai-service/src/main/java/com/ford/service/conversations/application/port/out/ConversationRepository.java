package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.domain.ConversationState;

import java.util.UUID;

public interface ConversationRepository {
    ConversationState state(UUID conversationId);

    boolean recordMessageIfNew(UUID conversationId, String providerMessageId);

    void transition(UUID conversationId, ConversationState expected, ConversationState target);
}
