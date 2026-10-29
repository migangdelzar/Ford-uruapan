package com.ford.service.conversations.api;

import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.leads.domain.LeadJourneySnapshot;

import java.util.UUID;

public record ConversationTurnRequest(
        UUID tenantId,
        UUID conversationId,
        String message,
        ConversationState state,
        ConversationMemoryContext memory,
        LeadJourneySnapshot leadJourney) {

    public ConversationTurnRequest(UUID tenantId, UUID conversationId,
                                   String message, ConversationState state) {
        this(tenantId, conversationId, message, state, null, null);
    }

    public ConversationTurnRequest(UUID tenantId, UUID conversationId, String message,
                                   ConversationState state, ConversationMemoryContext memory) {
        this(tenantId, conversationId, message, state, memory, null);
    }
}
