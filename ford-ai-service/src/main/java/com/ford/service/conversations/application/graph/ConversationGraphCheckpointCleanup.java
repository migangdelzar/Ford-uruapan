package com.ford.service.conversations.application.graph;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/** Removes inactive graph checkpoints without touching the durable transcript. */
class ConversationGraphCheckpointCleanup {

    private static final Logger log = LoggerFactory.getLogger(ConversationGraphCheckpointCleanup.class);

    private final JdbcClient jdbc;
    private final Duration retention;
    private final boolean resetOnStartup;

    ConversationGraphCheckpointCleanup(JdbcClient jdbc, Duration retention, boolean resetOnStartup) {
        this.jdbc = jdbc;
        this.retention = retention;
        this.resetOnStartup = resetOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    void resetOnStartup() {
        if (!resetOnStartup) {
            return;
        }
        var reset = new ConversationGraphCheckpointReset(jdbc);
        var removed = reset.reset();
        log.warn("Conversation graph checkpoints reset on startup: removedCheckpoints={} durableConversationDataPreserved=true",
                removed);
    }

    @Scheduled(fixedDelayString = "${ford.conversations.workflow.checkpoint-cleanup-delay-ms:3600000}")
    void removeExpiredCheckpoints() {
        jdbc.sql("delete from ford_graph.lg4jcheckpoint where saved_at < :cutoff")
                .param("cutoff", Timestamp.from(Instant.now().minus(retention)))
                .update();
    }
}
