package com.ford.service.conversations.graph;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class LangGraph4jCompatibilityTest {

    @Test
    void invokesTwoNodeGraphAndReturnsUpdatedState() throws Exception {
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);
        graph.addNode("first", (state, config) ->
                CompletableFuture.completedFuture(Map.of("first", "completed")));
        graph.addNode("second", (state, config) ->
                CompletableFuture.completedFuture(Map.of("result", "completed")));
        graph.addEdge(StateGraph.START, "first");
        graph.addEdge("first", "second");
        graph.addEdge("second", StateGraph.END);

        CompiledGraph<AgentState> compiled = graph.compile();
        AgentState result = compiled.invoke(
                GraphInput.args(Map.of("conversationRef", "conversation-1")),
                RunnableConfig.builder().threadId("tenant-1-conversation-1").build())
                .orElseThrow();

        assertThat(result.value("first")).contains("completed");
        assertThat(result.value("result")).contains("completed");
    }
}
