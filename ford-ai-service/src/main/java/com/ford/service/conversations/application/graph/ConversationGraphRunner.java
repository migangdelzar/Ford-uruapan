package com.ford.service.conversations.application.graph;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

import java.util.Map;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConversationGraphRunner {

    private static final Logger log = LoggerFactory.getLogger(ConversationGraphRunner.class);

    private final CompiledGraph<ConversationGraphState> graph;
    private final BaseCheckpointSaver checkpointSaver;

    public ConversationGraphRunner(ConversationTurnProcessor processor) {
        try {
            this.graph = ConversationGraphConfiguration.create(processor).compile();
            this.checkpointSaver = null;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not compile conversation graph", failure);
        }
    }

    public ConversationGraphRunner(ConversationTurnProcessor processor, BaseCheckpointSaver checkpointSaver) {
        try {
            this.graph = ConversationGraphConfiguration.compile(processor, checkpointSaver);
            this.checkpointSaver = checkpointSaver;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not compile conversation graph", failure);
        }
    }

    public ConversationGraphResult run(ConversationTurnReference reference) {
        var started = System.nanoTime();
        var config = RunnableConfig.builder().threadId(reference.checkpointThreadId()).build();
        final ConversationGraphState finalState;
        try {
            var resume = checkpointSaver != null && checkpointSaver.get(config).isPresent();
            log.info("Conversation graph started: conversationId={} receiptId={} checkpointResume={}",
                    reference.conversationId(), reference.receiptId(), resume);
            var input = resume ? GraphInput.resume() : GraphInput.args(Map.of(
                            ConversationGraphState.TENANT_ID, reference.tenantId().toString(),
                            ConversationGraphState.CONVERSATION_ID, reference.conversationId().toString(),
                            ConversationGraphState.RECEIPT_ID, reference.receiptId().toString()));
            finalState = graph.invoke(input, config)
                    .orElseThrow(() -> new IllegalStateException("Conversation graph returned no final state"));
        } catch (CompletionException failure) {
            var cause = failure.getCause();
            while (cause != null) {
                if (cause instanceof ConversationGraphExecutionException safeFailure) {
                    throw safeFailure;
                }
                cause = cause.getCause();
            }
            throw new ConversationGraphExecutionException(failure);
        } catch (ConversationGraphExecutionException failure) {
            log.error("Conversation graph failed: conversationId={} receiptId={} exceptionType={} durationMs={}",
                    reference.conversationId(), reference.receiptId(), failure.getClass().getSimpleName(),
                    elapsedMs(started), failure);
            throw failure;
        } catch (RuntimeException failure) {
            log.error("Conversation graph failed: conversationId={} receiptId={} exceptionType={} durationMs={}",
                    reference.conversationId(), reference.receiptId(), failure.getClass().getSimpleName(),
                    elapsedMs(started), failure);
            throw new ConversationGraphExecutionException(failure);
        }
        var result = new ConversationGraphResult(finalState.reference(), finalState.route(), finalState.terminal());
        log.info("Conversation graph finished: conversationId={} receiptId={} route={} terminal={} durationMs={}",
                reference.conversationId(), reference.receiptId(), result.route(), result.terminal(), elapsedMs(started));
        return result;
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
