package com.ford.service.conversations.application.graph;

@FunctionalInterface
public interface ConversationTurnProcessor {
    ConversationTurnOutcome process(ConversationTurnReference reference);
}
