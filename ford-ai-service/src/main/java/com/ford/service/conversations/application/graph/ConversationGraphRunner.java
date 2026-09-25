package com.ford.service.conversations.application.graph;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;

import java.util.Map;
import java.util.concurrent.CompletionException;

public final class ConversationGraphRunner {

    private final CompiledGraph<ConversationGraphState> graph;

    public ConversationGraphRunner(ConversationTurnProcessor processor) {
        try {
            this.graph = ConversationGraphConfiguration.create(processor).compile();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not compile conversation graph", failure);
        }
    }

    public ConversationGraphResult run(ConversationTurnReference reference) {
        var config = RunnableConfig.builder().threadId(reference.checkpointThreadId()).build();
        final ConversationGraphState finalState;
        try {
            finalState = graph.invoke(GraphInput.args(Map.of(
                            ConversationGraphState.TENANT_ID, reference.tenantId().toString(),
                            ConversationGraphState.CONVERSATION_ID, reference.conversationId().toString(),
                            ConversationGraphState.RECEIPT_ID, reference.receiptId().toString())), config)
                    .orElseThrow(() -> new IllegalStateException("Conversation graph returned no final state"));
        } catch (CompletionException failure) {
            var cause = failure.getCause();
            while (cause != null) {
                if (cause instanceof ConversationGraphExecutionException safeFailure) {
                    throw safeFailure;
                }
                cause = cause.getCause();
            }
            throw failure;
        }
        return new ConversationGraphResult(finalState.reference(), finalState.route(), finalState.terminal());
    }
}
