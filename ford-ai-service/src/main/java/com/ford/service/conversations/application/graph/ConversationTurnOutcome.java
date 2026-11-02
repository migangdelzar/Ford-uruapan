package com.ford.service.conversations.application.graph;

import java.util.Objects;

public record ConversationTurnOutcome(ConversationGraphRoute route) {

    public ConversationTurnOutcome {
        Objects.requireNonNull(route);
    }
}
