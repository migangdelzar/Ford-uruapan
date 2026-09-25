package com.ford.service.conversations.application.graph;

import org.bsc.langgraph4j.StateGraph;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class ConversationGraphConfiguration {

    private ConversationGraphConfiguration() {
    }

    static StateGraph<ConversationGraphState> create(ConversationTurnProcessor processor) throws Exception {
        var graph = new StateGraph<ConversationGraphState>(ConversationGraphState::from);
        graph.addNode("process_turn", (state, config) -> {
            try {
                var outcome = processor.process(state.reference());
                return CompletableFuture.completedFuture(Map.of(ConversationGraphState.ROUTE,
                        outcome.route().name()));
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(new ConversationGraphExecutionException(failure));
            }
        });
        for (var route : ConversationGraphRoute.values()) {
            graph.addNode(route.terminalNode(), (state, config) ->
                    CompletableFuture.completedFuture(Map.of(ConversationGraphState.TERMINAL, true)));
        }
        graph.addEdge(StateGraph.START, "process_turn");
        graph.addConditionalEdges("process_turn", state ->
                        CompletableFuture.completedFuture(state.route().terminalNode()),
                java.util.Arrays.stream(ConversationGraphRoute.values()).collect(java.util.stream.Collectors.toMap(
                        ConversationGraphRoute::terminalNode, ConversationGraphRoute::terminalNode)));
        for (var route : ConversationGraphRoute.values()) {
            graph.addEdge(route.terminalNode(), StateGraph.END);
        }
        return graph;
    }
}
