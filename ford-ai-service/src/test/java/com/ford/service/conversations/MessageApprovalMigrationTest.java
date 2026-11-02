package com.ford.service.conversations;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import com.ford.service.conversations.adapter.out.JdbcMessageApprovalRepository;
import com.ford.service.conversations.domain.AdvisorResponseDisposition;
import com.ford.service.conversations.domain.MessageApproval;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MessageApprovalMigrationTest {
    @Test void createsApprovalHistoryAuditAndConversationScopedHold() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:approval-migration-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            var jdbc = JdbcClient.create(dataSource);
            jdbc.sql("create table conversation(id uuid primary key, tenant_id uuid not null, channel varchar(30), provider_conversation_id varchar(200), state varchar(30), version bigint, created_at timestamp with time zone, updated_at timestamp with time zone)").update();
            jdbc.sql("create table channel_message(id uuid primary key, tenant_id uuid not null, conversation_id uuid not null, received_at timestamp with time zone not null)").update();
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            new Liquibase("db/changelog/changes/038-message-approval.yaml", new ClassLoaderResourceAccessor(), database)
                    .update(new Contexts(), new LabelExpression());
        }
        var jdbc = JdbcClient.create(dataSource);
        for (var table : new String[]{"MESSAGE_APPROVAL", "MESSAGE_APPROVAL_HOLD", "MESSAGE_APPROVAL_ACTION_AUDIT"}) {
            assertThat(jdbc.sql("select count(*) from information_schema.tables where table_name=:table")
                    .param("table", table).query(Integer.class).single()).isEqualTo(1);
        }
        assertThat(jdbc.sql("select count(*) from information_schema.columns where table_name='MESSAGE_APPROVAL' and column_name='DRAFT_TEXT'")
                .query(Integer.class).single()).isEqualTo(1);

        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var receipt = UUID.randomUUID();
        var now = Instant.parse("2026-10-28T16:30:00Z");
        jdbc.sql("insert into conversation(id,tenant_id,channel,provider_conversation_id,state,version,created_at,updated_at) values (:id,:tenant,'WHATSAPP',:provider,'BOT_ACTIVE',7,:at,:at)")
                .param("id", conversation).param("tenant", tenant).param("provider", conversation.toString()).param("at", now).update();
        jdbc.sql("insert into channel_message(id,tenant_id,conversation_id,received_at) values (:receipt,:tenant,:conversation,:at)")
                .param("tenant", tenant).param("conversation", conversation).param("receipt", receipt).param("at", now).update();
        var approval = MessageApproval.pending(UUID.randomUUID(), tenant, conversation, receipt, 7,
                new AdvisorResponseDisposition(AdvisorResponseDisposition.Route.REVIEW,
                        AdvisorResponseDisposition.Reason.RESPONSE_REVIEW), "Exact draft", true, now);
        var repository = new JdbcMessageApprovalRepository(jdbc);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var persisted = transactions.execute(tx -> repository.savePending(approval));
        assertThat(persisted.conversationVersion()).isEqualTo(8);
        assertThat(repository.list(tenant, conversation)).containsExactly(persisted);
        assertThat(jdbc.sql("select count(*) from message_approval_hold where tenant_id=:tenant")
                .param("tenant", tenant).query(Integer.class).single()).isEqualTo(1);
    }
}
