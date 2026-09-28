package com.ford.service.conversations.application.graph;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Clears only LangGraph execution state; durable conversations and memory remain untouched. */
public final class ConversationGraphCheckpointReset {

    private final JdbcClient jdbc;

    public ConversationGraphCheckpointReset(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int reset() {
        var checkpoints = jdbc.sql("select count(*) from ford_graph.lg4jcheckpoint")
                .query(Integer.class)
                .single();
        jdbc.sql("delete from ford_graph.lg4jcheckpoint").update();
        jdbc.sql("delete from ford_graph.lg4jthreadtag").update();
        jdbc.sql("delete from ford_graph.lg4jthread").update();
        return checkpoints;
    }
}
