package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationTurn;
import com.ford.service.conversations.domain.HandoffDecision;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository {
    Optional<ConversationSnapshot> find(UUID conversationId);

    boolean completeBotTurn(UUID conversationId, long expectedVersion, ConversationTurn turn);

    boolean requestHumanHandoff(UUID conversationId, long expectedVersion, HandoffDecision decision);

    ConversationState state(UUID conversationId);

    boolean recordMessageIfNew(UUID conversationId, String providerMessageId);

    void transition(UUID conversationId, ConversationState expected, ConversationState target);
}
