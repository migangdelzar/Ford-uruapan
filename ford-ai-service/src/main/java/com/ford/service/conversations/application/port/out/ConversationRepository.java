package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationTurn;
import com.ford.service.conversations.domain.HandoffDecision;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository {
    Optional<ConversationSnapshot> find(UUID conversationId);

    default Optional<ConversationSnapshot> find(UUID tenantId, UUID conversationId) {
        return find(conversationId).filter(snapshot -> snapshot.tenantId().equals(tenantId));
    }

    boolean completeBotTurn(UUID conversationId, long expectedVersion, ConversationTurn turn);

    default boolean completeBotTurn(UUID tenantId, UUID conversationId, long expectedVersion,
                                    String inboundProviderMessageId, ConversationTurn turn) {
        if (find(tenantId, conversationId).isEmpty()) {
            return false;
        }
        return completeBotTurn(conversationId, expectedVersion, turn);
    }

    boolean requestHumanHandoff(UUID conversationId, long expectedVersion, HandoffDecision decision);

    default boolean requestHumanHandoff(UUID tenantId, UUID conversationId, long expectedVersion,
                                        HandoffDecision decision) {
        if (find(tenantId, conversationId).isEmpty()) {
            return false;
        }
        return requestHumanHandoff(conversationId, expectedVersion, decision);
    }

    default boolean requestHumanHandoff(UUID tenantId, UUID conversationId, long expectedVersion,
                                        String inboundProviderMessageId, HandoffDecision decision,
                                        ConversationTurn confirmation) {
        return requestHumanHandoff(tenantId, conversationId, expectedVersion, decision);
    }

    default boolean turnAlreadyCompleted(UUID tenantId, UUID conversationId, String inboundProviderMessageId) {
        return false;
    }

    ConversationState state(UUID conversationId);

    boolean recordMessageIfNew(UUID conversationId, String providerMessageId);

    void transition(UUID conversationId, ConversationState expected, ConversationState target);
}
