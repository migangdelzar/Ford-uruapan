package com.ford.service.conversations;

import com.ford.service.conversations.adapter.out.JdbcDecisionTraceRepository;
import com.ford.service.ai.application.DecisionTraceEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionTraceRepositoryTest {

    @Test
    void appendsOrderedContentFreeEventsAndScopesReadsToTenantReceipt() throws Exception {
        var jdbc = migratedDatabase();
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var receipt = UUID.randomUUID();
        seedTurn(jdbc, tenant, conversation, receipt);
        var repository = new JdbcDecisionTraceRepository(jdbc);
        var timestamp = Instant.parse("2026-10-21T12:00:00Z");
        var events = List.of(
                event("route", "completed", timestamp),
                event("tool", "returned", timestamp.plusMillis(1)));

        repository.append(tenant, conversation, receipt, events);
        repository.append(tenant, conversation, receipt, events);

        assertThat(repository.list(tenant, conversation, receipt)).containsExactlyElementsOf(events);
        assertThat(repository.list(UUID.randomUUID(), conversation, receipt)).isEmpty();
        assertThat(repository.list(tenant, conversation, UUID.randomUUID())).isEmpty();
        assertThat(jdbc.sql("select count(*) from conversation_decision_event where tenant_id=:tenant")
                .param("tenant", tenant).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from information_schema.columns where table_name='CONVERSATION_DECISION_EVENT' "
                        + "and column_name in ('MESSAGE','PROMPT','REPLY','RESULT','CONTENT','ARGUMENTS')")
                .query(Integer.class).single()).isZero();
    }

    private static DecisionTraceEvent event(String stage, String outcome, Instant at) {
        return new DecisionTraceEvent(stage, "fixture_event", "VEHICLE_DETAILS", "answer",
                stage.equals("tool") ? "search_vehicles" : null, outcome, 2, at);
    }

    private static JdbcClient migratedDatabase() throws Exception {
        Class.forName("org.h2.Driver");
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:decision-trace-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var database = liquibase.database.DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                new liquibase.database.jvm.JdbcConnection(dataSource.getConnection()));
        var liquibase = new liquibase.Liquibase("db/changelog/changes/030-conversation-turn-ledger.yaml",
                new liquibase.resource.ClassLoaderResourceAccessor(), database);
        liquibase.update(new liquibase.Contexts(), new liquibase.LabelExpression());
        var traceMigration = new liquibase.Liquibase("db/changelog/changes/031-conversation-decision-events.yaml",
                new liquibase.resource.ClassLoaderResourceAccessor(), database);
        traceMigration.update(new liquibase.Contexts(), new liquibase.LabelExpression());
        return JdbcClient.create(dataSource);
    }

    private static void seedTurn(JdbcClient jdbc, UUID tenant, UUID conversation, UUID receipt) {
        var now = Instant.parse("2026-10-21T11:59:00Z");
        jdbc.sql("insert into conversation_turn(tenant_id,conversation_id,receipt_id,provider_message_id,state,accepted_at,updated_at) "
                        + "values (:tenant,:conversation,:receipt,'synthetic-message','PROCESSING',:now,:now)")
                .param("tenant", tenant).param("conversation", conversation).param("receipt", receipt)
                .param("now", now).update();
    }
}
