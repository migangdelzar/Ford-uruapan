package com.ford.service.conversations;

import com.ford.service.conversations.application.ConversationResetInventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ConversationResetInventoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();

    @Test
    void countsOnlyRequestedTenantAndReportsPreservedBindingsAndDedupeReceiptsWithoutMutation() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var connection = dataSource.getConnection()) {
            var database = liquibase.database.DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new liquibase.database.jvm.JdbcConnection(connection));
            new liquibase.Liquibase("db/changelog/db.changelog-master.yaml",
                    new liquibase.resource.ClassLoaderResourceAccessor(), database)
                    .update(new liquibase.Contexts(), new liquibase.LabelExpression());
        }
        var jdbc = JdbcClient.create(dataSource);
        var firstTenant = UUID.randomUUID();
        var secondTenant = UUID.randomUUID();
        var firstConversation = UUID.randomUUID();
        var secondConversation = UUID.randomUUID();
        var firstReceipt = UUID.randomUUID();
        var secondReceipt = UUID.randomUUID();
        seed(jdbc, firstTenant, firstConversation, firstReceipt, "first");
        seed(jdbc, secondTenant, secondConversation, secondReceipt, "second");

        var beforeDeliveryCount = jdbc.sql("select count(*) from channel_delivery").query(Long.class).single();
        var beforeConversationCount = jdbc.sql("select count(*) from conversation").query(Long.class).single();

        var inventory = new ConversationResetInventoryService(jdbc).inventory(firstTenant);

        assertThat(inventory.counts()).containsEntry("conversation", 1L)
                .containsEntry("conversation_message", 1L)
                .containsEntry("channel_message", 1L)
                .containsEntry("conversation_turn", 0L)
                .containsEntry("conversation_review_item", 0L);
        assertThat(inventory.preservedCounts()).containsEntry("channel_binding", 1L)
                .containsEntry("channel_delivery_dedupe_tombstone", 1L);
        assertThat(new java.util.ArrayList<>(inventory.counts().keySet()))
                .isEqualTo(inventory.counts().keySet().stream().sorted().toList());
        assertThat(jdbc.sql("select count(*) from channel_delivery").query(Long.class).single())
                .isEqualTo(beforeDeliveryCount);
        assertThat(jdbc.sql("select count(*) from conversation").query(Long.class).single())
                .isEqualTo(beforeConversationCount);
        assertThat(jdbc.sql("select count(*) from channel_delivery where receipt_id in (:first, :second)")
                .param("first", firstReceipt).param("second", secondReceipt).query(Long.class).single()).isEqualTo(2);
        assertThat(secondConversation).isNotEqualTo(firstConversation);
    }

    @Test
    void failsClosedWhenTenantlessAuditRecordReferencesRequestedConversation() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = JdbcClient.create(dataSource);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        seed(jdbc, tenantId, conversationId, UUID.randomUUID(), "ambiguous");
        jdbc.sql("insert into audit_event (id, tenant_id, actor_type, event_type, aggregate_type, aggregate_id, occurred_at) values (:id, null, 'SYSTEM', 'synthetic', 'CONVERSATION', :conversation, :now)")
                .param("id", UUID.randomUUID()).param("conversation", conversationId)
                .param("now", java.sql.Timestamp.from(Instant.now())).update();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new ConversationResetInventoryService(jdbc).inventory(tenantId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous");
    }

    private static void seed(JdbcClient jdbc, UUID tenantId, UUID conversationId, UUID receiptId, String suffix) {
        var now = Instant.parse("2026-10-20T12:00:00Z");
        var bindingId = UUID.randomUUID();
        jdbc.sql("insert into channel_binding (id, webhook_key_hash, tenant_id, channel, provider_account_id, secret_reference, enabled, created_at, updated_at) values (:id, :hash, :tenant, 'WHATSAPP', :account, 'secret-ref', true, :now, :now)")
                .param("id", bindingId).param("hash", UUID.randomUUID().toString()).param("tenant", tenantId)
                .param("account", "account-" + suffix).param("now", java.sql.Timestamp.from(now)).update();
        jdbc.sql("insert into conversation (id, tenant_id, channel, provider_conversation_id, state, version, created_at, updated_at, provider_account_id, participant_id) values (:id, :tenant, 'WHATSAPP', :external, 'BOT_ACTIVE', 0, :now, :now, :account, :participant)")
                .param("id", conversationId).param("tenant", tenantId).param("external", "conversation-" + suffix)
                .param("account", "account-" + suffix).param("participant", "synthetic-participant-" + suffix)
                .param("now", java.sql.Timestamp.from(now)).update();
        jdbc.sql("insert into conversation_message (id, conversation_id, direction, provider_message_id, content, received_at) values (:id, :conversation, 'INBOUND', :provider, 'synthetic fixture', :now)")
                .param("id", UUID.randomUUID()).param("conversation", conversationId)
                .param("provider", "provider-" + suffix).param("now", java.sql.Timestamp.from(now)).update();
        jdbc.sql("insert into channel_delivery (receipt_id, binding_id, conversation_id, tenant_id, channel, provider_message_id, received_at, raw_payload_reference) values (:receipt, :binding, :conversation, :tenant, 'WHATSAPP', :provider, :now, 'synthetic')")
                .param("receipt", receiptId).param("binding", bindingId).param("conversation", conversationId)
                .param("tenant", tenantId).param("provider", "provider-" + suffix)
                .param("now", java.sql.Timestamp.from(now)).update();
        jdbc.sql("insert into channel_message (id, binding_id, conversation_id, tenant_id, channel, provider_message_id, participant_id, content, received_at, raw_payload_reference) values (:id, :binding, :conversation, :tenant, 'WHATSAPP', :provider, 'synthetic-participant', 'synthetic fixture', :now, 'synthetic')")
                .param("id", UUID.randomUUID()).param("binding", bindingId).param("conversation", conversationId)
                .param("tenant", tenantId).param("provider", "provider-" + suffix)
                .param("now", java.sql.Timestamp.from(now)).update();
    }
}
