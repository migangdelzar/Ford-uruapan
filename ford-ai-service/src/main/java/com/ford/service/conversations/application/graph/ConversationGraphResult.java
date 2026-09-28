package com.ford.service.conversations.application.graph;

public record ConversationGraphResult(ConversationTurnReference reference,
                                      ConversationGraphRoute route,
                                      boolean terminal) {
}
