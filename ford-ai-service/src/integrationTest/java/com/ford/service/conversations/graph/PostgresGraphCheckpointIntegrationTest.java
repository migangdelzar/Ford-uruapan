package com.ford.service.conversations.graph;

import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationGraphRunner;
import com.ford.service.conversations.application.graph.ConversationTurnOutcome;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.graph.FordPostgresSaverV2;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresGraphCheckpointIntegrationTest {

    private static final String CHECKPOINT_SCHEMA = "ford_graph";
    private static final String PRIVATE_TEXT_MARKER = "customer-message-must-not-be-checkpointed";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();

    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @BeforeEach
    void initializeCheckpointSchema() throws Exception {
        migrateCheckpointSchema();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("truncate table ford_graph.lg4jcheckpoint, ford_graph.lg4jthreadtag, "
                    + "ford_graph.lg4jthread restart identity");
        }
    }

    @AfterEach
    void closeCheckpointDataSources() {
        dataSources.forEach(HikariDataSource::close);
        dataSources.clear();
    }

    @Test
    void restoresSafeCheckpointThroughANewSaverAndDoesNotPersistConversationText() throws Exception {
        var reference = reference();
        var firstSaver = saver();
        var processorCalls = new AtomicInteger();
        var runner = new ConversationGraphRunner(ignored -> {
            processorCalls.incrementAndGet();
            return new ConversationTurnOutcome(ConversationGraphRoute.BOT_REPLY);
        }, firstSaver);

        var result = runner.run(reference);
        for (var retry = 0; retry < 10; retry++) {
            runner.run(reference);
        }

        var reopenedSaver = saver();
        var checkpoints = reopenedSaver.list(RunnableConfig.builder()
                .threadId(reference.checkpointThreadId()).build());
        assertThat(result.terminal()).isTrue();
        assertThat(processorCalls).hasValue(1);
        assertThat(checkpoints).isNotEmpty();
        assertThat(checkpoints.stream().findFirst().orElseThrow().getState())
                .containsEntry("tenantId", reference.tenantId().toString())
                .containsEntry("conversationId", reference.conversationId().toString())
                .containsEntry("receiptId", reference.receiptId().toString());
        var allowedKeys = Set.of("tenantId", "conversationId", "receiptId", "route", "terminal");
        assertThat(checkpoints).allSatisfy(checkpoint -> {
            assertThat(checkpoint.getState().keySet()).allMatch(allowedKeys::contains);
            assertThat(checkpoint.getState().toString()).doesNotContain(PRIVATE_TEXT_MARKER);
        });
        assertThat(storedCheckpointJson()).doesNotContain(PRIVATE_TEXT_MARKER);
    }

    @Test
    void keepsDifferentTenantConversationReceiptThreadsIsolated() throws Exception {
        var saver = saver();
        var first = reference();
        var second = new ConversationTurnReference(UUID.randomUUID(), first.conversationId(), UUID.randomUUID());
        var runner = new ConversationGraphRunner(ignored ->
                new ConversationTurnOutcome(ConversationGraphRoute.IGNORED), saver);

        runner.run(first);
        runner.run(second);

        assertThat(saver.list(RunnableConfig.builder().threadId(first.checkpointThreadId()).build())).isNotEmpty();
        assertThat(saver.list(RunnableConfig.builder().threadId(second.checkpointThreadId()).build())).isNotEmpty();
        assertThat(countCheckpointThreads()).isEqualTo(2);
    }

    @Test
    void processesEachNewReceiptInTheSameConversationButResumesDuplicateReceipt() throws Exception {
        var saver = saver();
        var first = reference();
        var second = new ConversationTurnReference(first.tenantId(), first.conversationId(), UUID.randomUUID());
        var processorCalls = new AtomicInteger();
        var runner = new ConversationGraphRunner(ignored -> {
            processorCalls.incrementAndGet();
            return new ConversationTurnOutcome(ConversationGraphRoute.BOT_REPLY);
        }, saver);

        runner.run(first);
        runner.run(first);
        runner.run(second);

        assertThat(processorCalls).hasValue(2);
        assertThat(saver.list(RunnableConfig.builder().threadId(first.checkpointThreadId()).build())).isNotEmpty();
        assertThat(saver.list(RunnableConfig.builder().threadId(second.checkpointThreadId()).build())).isNotEmpty();
        assertThat(countCheckpointThreads()).isEqualTo(2);
    }

    @Test
    void resumesFromPostProcessorCheckpointWithoutInvokingProcessorAgain() throws Exception {
        var reference = reference();
        var saver = saver();
        var config = RunnableConfig.builder().threadId(reference.checkpointThreadId()).build();
        saver.put(config, Checkpoint.builder()
                .state(java.util.Map.of(
                        "tenantId", reference.tenantId().toString(),
                        "conversationId", reference.conversationId().toString(),
                        "receiptId", reference.receiptId().toString(),
                        "route", ConversationGraphRoute.BOT_REPLY.name()))
                .nodeId("process_turn")
                .nextNodeId(ConversationGraphRoute.BOT_REPLY.terminalNode())
                .build());
        var processorCalls = new AtomicInteger();
        var runner = new ConversationGraphRunner(ignored -> {
            processorCalls.incrementAndGet();
            return new ConversationTurnOutcome(ConversationGraphRoute.HUMAN_PENDING);
        }, saver);

        var result = runner.run(reference);

        assertThat(processorCalls).hasValue(0);
        assertThat(result.route()).isEqualTo(ConversationGraphRoute.BOT_REPLY);
        assertThat(result.terminal()).isTrue();
    }

    private static ConversationTurnReference reference() {
        return new ConversationTurnReference(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static void migrateCheckpointSchema() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            var liquibase = new Liquibase("db/changelog/langgraph/db.changelog.yaml",
                    new ClassLoaderResourceAccessor(), database);
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }

    private FordPostgresSaverV2 saver() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setSchema(CHECKPOINT_SCHEMA);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        var dataSource = new HikariDataSource(config);
        dataSources.add(dataSource);
        return FordPostgresSaverV2.create(dataSource);
    }

    private static String storedCheckpointJson() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl() + "?currentSchema=" + CHECKPOINT_SCHEMA,
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "select string_agg(state_data::text, ',') from ford_graph.lg4jcheckpoint")) {
            result.next();
            return result.getString(1);
        }
    }

    private static int countCheckpointThreads() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl() + "?currentSchema=" + CHECKPOINT_SCHEMA,
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
                var result = statement.executeQuery("select count(*) from ford_graph.lg4jthread")) {
            result.next();
            return result.getInt(1);
        }
    }
}
