package com.ford.service.conversations.application.graph;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationGraphCheckpointCleanupTest {

    @Test
    void removesOnlyCheckpointsPastRetention() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(any(String.class))).thenReturn(statement);
        when(statement.param(eq("cutoff"), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);

        new ConversationGraphCheckpointCleanup(jdbc, Duration.ofDays(90)).removeExpiredCheckpoints();

        verify(jdbc).sql("delete from ford_graph.lg4jcheckpoint where saved_at < :cutoff");
        verify(statement).param(eq("cutoff"), any());
        verify(statement).update();
    }
}
