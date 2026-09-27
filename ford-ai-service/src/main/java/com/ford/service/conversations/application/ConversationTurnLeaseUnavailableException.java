package com.ford.service.conversations.application;

import java.util.UUID;

/** Signals that the durable inbound event must remain incomplete for a later retry. */
public final class ConversationTurnLeaseUnavailableException extends RuntimeException {

    public ConversationTurnLeaseUnavailableException(UUID conversationId) {
        super("Conversation turn lease unavailable for conversation " + conversationId);
    }
}
