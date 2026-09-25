package com.ford.service.conversations.application.graph;

public final class ConversationGraphExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ConversationGraphExecutionException(RuntimeException failure) {
        super("Conversation turn processor failed (" + failure.getClass().getSimpleName() + ")");
    }
}
