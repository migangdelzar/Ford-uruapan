package com.ford.service.conversations.application.graph;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/** Removes inactive graph checkpoints without touching the durable transcript. */
class ConversationGraphCheckpointCleanup {

    private final JdbcClient jdbc;
    private final Duration retention;

    ConversationGraphCheckpointCleanup(JdbcClient jdbc, Duration retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${ford.conversations.workflow.checkpoint-cleanup-delay-ms:3600000}")
    void removeExpiredCheckpoints() {
        jdbc.sql("delete from ford_graph.lg4jcheckpoint where saved_at < :cutoff")
                .param("cutoff", Timestamp.from(Instant.now().minus(retention)))
                .update();
    }
}
