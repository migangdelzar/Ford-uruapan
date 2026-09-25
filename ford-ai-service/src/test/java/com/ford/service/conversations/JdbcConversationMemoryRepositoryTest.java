package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.out.JdbcConversationMemoryRepository;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryFact;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcConversationMemoryRepositoryTest {

    private JdbcClient jdbc;

    private JdbcConversationMemoryRepository repository;
    private ConversationMemoryKey key;

    @BeforeEach
    void setUp() {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("H2 test driver is required", exception);
        }
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:memory;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("drop table if exists conversation_memory").update();
        jdbc.sql("drop table if exists conversation_message").update();
        jdbc.sql("drop table if exists conversation").update();
        jdbc.sql("""
                create table conversation (
                    id uuid primary key,
                    tenant_id uuid not null,
                    channel varchar(30) not null,
                    provider_account_id varchar(200) not null,
                    participant_id varchar(200) not null
                )
                """).update();
        jdbc.sql("""
                create table conversation_message (
                    id uuid primary key,
                    conversation_id uuid not null,
                    direction varchar(20) not null,
                    provider_message_id varchar(200) not null,
                    content text,
                    received_at timestamp with time zone not null
                )
                """).update();
        jdbc.sql("""
                create table conversation_memory (
                    conversation_id uuid primary key,
                    tenant_id uuid not null,
                    channel varchar(30) not null,
                    provider_account_id varchar(200) not null,
                    participant_id varchar(200) not null,
                    summary text not null,
                    facts_json varchar not null,
                    memory_version bigint not null,
                    source_message_id uuid,
                    updated_at timestamp with time zone not null
                )
                """).update();

        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        key = new ConversationMemoryKey(tenantId, Channel.WHATSAPP,
                "phone-1", "customer-1", conversationId);
        var now = Instant.parse("2026-09-21T12:00:00Z");
        jdbc.sql("insert into conversation values (:id, :tenant, :channel, :account, :participant)")
                .param("id", conversationId)
                .param("tenant", tenantId)
                .param("channel", "WHATSAPP")
                .param("account", "phone-1")
                .param("participant", "customer-1")
                .update();
        jdbc.sql("insert into conversation_memory values (:id, :tenant, :channel, :account, :participant, :summary, :facts, 0, null, :updated)")
                .param("id", conversationId)
                .param("tenant", tenantId)
                .param("channel", "WHATSAPP")
                .param("account", "phone-1")
                .param("participant", "customer-1")
                .param("summary", "Busca una Ranger")
                .param("facts", "[{\"kind\":\"preference\",\"content\":\"Ranger\"}]")
                .param("updated", now)
                .update();
        insertMessage("in-1", ConversationMessage.Direction.INBOUND, "Hola", now.minusSeconds(2));
        insertMessage("out-1", ConversationMessage.Direction.OUTBOUND, "¿Qué versión buscas?", now.minusSeconds(1));
        insertMessage("in-2", ConversationMessage.Direction.INBOUND, "Ranger", now);
        repository = new JdbcConversationMemoryRepository(jdbc, new ObjectMapper(), new MemoryProperties());
    }

    @Test
    void loadsSummaryFactsAndNewestMessagesInChronologicalOrder() {
        var context = repository.load(new MemoryQuery(key, "Ranger", 2, 5));

        assertThat(context.summary()).isEqualTo("Busca una Ranger");
        assertThat(context.durableFacts()).containsExactly(new MemoryFact("preference", "Ranger", null));
        assertThat(context.recentMessages()).extracting(ConversationMessage::content)
                .containsExactly("¿Qué versión buscas?", "Ranger");
    }

    @Test
    void rejectsTranscriptAndProjectionWhenAnyConversationIdentityComponentDiffers() {
        var mismatches = List.of(
                new ConversationMemoryKey(UUID.randomUUID(), key.channel(), key.providerAccountId(),
                        key.participantId(), key.conversationId()),
                new ConversationMemoryKey(key.tenantId(), com.ford.service.channels.domain.Channel.MESSENGER,
                        key.providerAccountId(), key.participantId(), key.conversationId()),
                new ConversationMemoryKey(key.tenantId(), key.channel(), "another-phone",
                        key.participantId(), key.conversationId()),
                new ConversationMemoryKey(key.tenantId(), key.channel(), key.providerAccountId(),
                        "another-customer", key.conversationId()));

        for (var mismatch : mismatches) {
            var context = repository.load(new MemoryQuery(mismatch, null, 5, 5));

            assertThat(context.summary()).isEmpty();
            assertThat(context.durableFacts()).isEmpty();
            assertThat(context.recentMessages()).as("mismatch %s", mismatch).isEmpty();
        }
    }

    @Test
    void rejectsAStaleProjectionVersion() {
        var update = new MemoryProjectionUpdate("Nuevo resumen", List.of(), null);

        var applied = repository.updateProjection(key, update, 0);
        var stale = repository.updateProjection(key, update, 0);

        assertThat(applied).isEqualTo(new com.ford.service.conversations.domain.MemoryUpdateResult(true, 1));
        assertThat(stale.applied()).isFalse();
    }

    private void insertMessage(String providerId, ConversationMessage.Direction direction,
                                String content, Instant receivedAt) {
        jdbc.sql("insert into conversation_message values (:id, :conversation, :direction, :provider, :content, :received)")
                .param("id", UUID.randomUUID())
                .param("conversation", key.conversationId())
                .param("direction", direction.name())
                .param("provider", providerId)
                .param("content", content)
                .param("received", receivedAt)
                .update();
    }
}
