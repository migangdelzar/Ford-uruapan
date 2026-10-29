package com.ford.service.conversations;

import com.ford.service.conversations.adapter.out.JdbcConversationTurnLedgerAdapter;
import com.ford.service.conversations.application.ConversationTurnState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcConversationTurnLedgerAdapterTest {

    @Test
    void countsOnlyConsecutiveSubstantiveFailuresAndSuccessfulReplyResetsCount() throws Exception {
        Class.forName("org.h2.Driver");
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:conversation-turn-ledger-failure-count;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var migration = new liquibase.Liquibase("db/changelog/changes/030-conversation-turn-ledger.yaml",
                new liquibase.resource.ClassLoaderResourceAccessor(),
                liquibase.database.DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                        new liquibase.database.jvm.JdbcConnection(dataSource.getConnection())));
        migration.update(new liquibase.Contexts(), new liquibase.LabelExpression());
        JdbcClient.create(dataSource).sql("create table message_approval(tenant_id uuid, conversation_id uuid, source_receipt_id uuid, disposition_route varchar(20), reason_code varchar(80))").update();
        var adapter = new JdbcConversationTurnLedgerAdapter(JdbcClient.create(dataSource));
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var now = Instant.parse("2026-10-28T18:00:00Z");

        adapter.recordOutcome(tenant, conversation, UUID.randomUUID(), "failure-1",
                ConversationTurnState.SAFE_FALLBACK_REVIEW, "assistant_unavailable", now);
        adapter.recordOutcome(tenant, conversation, UUID.randomUUID(), "retry-only",
                ConversationTurnState.RETRY_WAIT, "infrastructure_retry", now.plusSeconds(1));
        adapter.recordOutcome(tenant, conversation, UUID.randomUUID(), "failure-2",
                ConversationTurnState.SAFE_FALLBACK_REVIEW, "invalid_response", now.plusSeconds(2));

        assertThat(adapter.consecutiveSubstantiveFailures(tenant, conversation)).isEqualTo(2);

        adapter.recordOutcome(tenant, conversation, UUID.randomUUID(), "success",
                ConversationTurnState.REPLY_COMMITTED, null, now.plusSeconds(3));
        assertThat(adapter.consecutiveSubstantiveFailures(tenant, conversation)).isZero();
    }

    @Test
    void recordsFallbackOutcomeAndReviewOnceWithoutStoringMessageText() throws Exception {
        Class.forName("org.h2.Driver");
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:conversation-turn-ledger-adapter;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var migration = new liquibase.Liquibase("db/changelog/changes/030-conversation-turn-ledger.yaml",
                new liquibase.resource.ClassLoaderResourceAccessor(),
                liquibase.database.DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                        new liquibase.database.jvm.JdbcConnection(dataSource.getConnection())));
        migration.update(new liquibase.Contexts(), new liquibase.LabelExpression());
        JdbcClient.create(dataSource).sql("create table message_approval(tenant_id uuid, conversation_id uuid, source_receipt_id uuid, disposition_route varchar(20), reason_code varchar(80))").update();

        var adapter = new JdbcConversationTurnLedgerAdapter(JdbcClient.create(dataSource));
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var now = Instant.parse("2026-10-20T12:00:00Z");

        adapter.recordOutcome(tenantId, conversationId, receiptId, "provider-message-1",
                ConversationTurnState.SAFE_FALLBACK_REVIEW, "invalid_model_output", now);
        adapter.recordReview(tenantId, conversationId, receiptId, "invalid_model_output", now);
        adapter.recordReview(tenantId, conversationId, receiptId, "invalid_model_output", now);

        var jdbc = JdbcClient.create(dataSource);
        assertThat(jdbc.sql("select state from conversation_turn where tenant_id = :tenant")
                .param("tenant", tenantId).query(String.class).single()).isEqualTo("SAFE_FALLBACK_REVIEW");
        assertThat(jdbc.sql("select count(*) from conversation_review_item where tenant_id = :tenant")
                .param("tenant", tenantId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from information_schema.columns "
                        + "where table_name = 'CONVERSATION_REVIEW_ITEM' and column_name = 'CONTENT'")
                .query(Integer.class).single()).isZero();

        var operatorId = UUID.randomUUID();
        assertThat(adapter.list(tenantId, "PENDING", 10)).hasSize(1);
        assertThat(adapter.list(UUID.randomUUID(), "PENDING", 10)).isEmpty();
        var reviewId = jdbc.sql("select id from conversation_review_item where tenant_id = :tenant")
                .param("tenant", tenantId).query(UUID.class).single();
        assertThat(adapter.resolve(UUID.randomUUID(), reviewId, operatorId, now.plusSeconds(1))).isFalse();
        assertThat(adapter.resolve(tenantId, reviewId, operatorId, now.plusSeconds(1))).isTrue();
        assertThat(adapter.list(tenantId, "PENDING", 10)).isEmpty();
        assertThat(jdbc.sql("select reviewed_by from conversation_review_item where id = :id")
                .param("id", reviewId).query(UUID.class).single()).isEqualTo(operatorId);
    }
}
