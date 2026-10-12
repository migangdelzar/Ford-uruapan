package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.out.JdbcConversationMemoryRepository;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryFact;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryQuery;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = "spring.modulith.runtime.verification-enabled=false")
@ActiveProfiles("test")
class ConversationMemoryPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final ConversationMemoryKey MEMORY_KEY = new ConversationMemoryKey(
            TENANT_ID, Channel.WHATSAPP, "phone-number-1", "5214521234567", CONVERSATION_ID);

    @BeforeAll
    static void prepareSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JdbcConversationMemoryRepository repository;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from conversation_memory where conversation_id = :id")
                .param("id", CONVERSATION_ID).update();
        jdbc.sql("delete from conversation_message where conversation_id = :id")
                .param("id", CONVERSATION_ID).update();
        jdbc.sql("delete from channel_message where tenant_id = :tenantId")
                .param("tenantId", TENANT_ID).update();
        jdbc.sql("delete from conversation where id = :id")
                .param("id", CONVERSATION_ID).update();
    }

    @Test
    void writesAndReloadsFactsJsonbInPostgres() {
        insertConversation();
        var fact = new MemoryFact("preference", "Ranger", null);

        var result = repository.updateProjection(
                MEMORY_KEY,
                new MemoryProjectionUpdate("Busca una Ranger", List.of(fact), null),
                0);
        var context = repository.load(new MemoryQuery(MEMORY_KEY, null, 0, 5));

        assertThat(result.applied()).isTrue();
        assertThat(context.durableFacts()).containsExactly(fact);
    }

    @Test
    void doesNotLoadTranscriptForMismatchedConversationIdentity() {
        insertConversation();
        jdbc.sql("""
                insert into conversation_message (id, conversation_id, direction, provider_message_id, content, received_at)
                values (:id, :conversationId, 'INBOUND', 'message-1', 'private transcript', :receivedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("conversationId", CONVERSATION_ID)
                .param("receivedAt", Timestamp.from(Instant.now()))
                .update();

        var mismatches = List.of(
                new ConversationMemoryKey(UUID.randomUUID(), MEMORY_KEY.channel(), MEMORY_KEY.providerAccountId(),
                        MEMORY_KEY.participantId(), CONVERSATION_ID),
                new ConversationMemoryKey(MEMORY_KEY.tenantId(), Channel.MESSENGER,
                        MEMORY_KEY.providerAccountId(), MEMORY_KEY.participantId(), CONVERSATION_ID),
                new ConversationMemoryKey(MEMORY_KEY.tenantId(), MEMORY_KEY.channel(), "another-phone",
                        MEMORY_KEY.participantId(), CONVERSATION_ID),
                new ConversationMemoryKey(MEMORY_KEY.tenantId(), MEMORY_KEY.channel(), MEMORY_KEY.providerAccountId(),
                        "another-customer", CONVERSATION_ID));

        for (var mismatch : mismatches) {
            var context = repository.load(new MemoryQuery(mismatch, null, 5, 5));

            assertThat(context.summary()).isEmpty();
            assertThat(context.durableFacts()).isEmpty();
            assertThat(context.recentMessages()).isEmpty();
        }
    }

    @Test
    void restartStartsNewMemoryEpochAndPreservesHistoricalTranscript() {
        insertConversation();
        var beforeReset = Instant.now().minusSeconds(5);
        jdbc.sql("""
                insert into conversation_message (id, conversation_id, direction, provider_message_id, content, received_at)
                values (:id, :conversationId, 'INBOUND', 'before-reset', 'Busca Ranger', :receivedAt)
                """)
                .param("id", UUID.randomUUID()).param("conversationId", CONVERSATION_ID)
                .param("receivedAt", Timestamp.from(beforeReset)).update();
        repository.updateProjection(MEMORY_KEY,
                new MemoryProjectionUpdate("Busca Ranger", List.of(new MemoryFact("model", "Ranger", null)), null), 0);
        jdbc.sql("""
                insert into conversation_memory_item
                    (id, conversation_id, tenant_id, channel, kind, content, embedding, active, created_at, updated_at)
                values (:id, :conversationId, :tenantId, 'WHATSAPP', 'preference', 'Ranger', '[0.1,0.2]'::vector,
                        true, :createdAt, :createdAt)
                """)
                .param("id", UUID.randomUUID()).param("conversationId", CONVERSATION_ID).param("tenantId", TENANT_ID)
                .param("createdAt", Timestamp.from(beforeReset)).update();
        var resetAt = Instant.now();

        repository.resetContext(MEMORY_KEY, UUID.randomUUID(), resetAt);
        jdbc.sql("""
                insert into conversation_message (id, conversation_id, direction, provider_message_id, content, received_at)
                values (:id, :conversationId, 'INBOUND', 'after-reset', 'Tema nuevo', :receivedAt)
                """)
                .param("id", UUID.randomUUID()).param("conversationId", CONVERSATION_ID)
                .param("receivedAt", Timestamp.from(resetAt.plusMillis(5))).update();

        var context = repository.load(new MemoryQuery(MEMORY_KEY, null, 10, 10));

        assertThat(context.summary()).isEmpty();
        assertThat(context.durableFacts()).isEmpty();
        assertThat(context.recentMessages()).extracting(message -> message.providerMessageId())
                .containsExactly("after-reset");
        assertThat(jdbc.sql("select count(*) from conversation_message where conversation_id = :id")
                .param("id", CONVERSATION_ID).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select active from conversation_memory_item where conversation_id = :id")
                .param("id", CONVERSATION_ID).query(Boolean.class).single()).isFalse();
    }

    private void insertConversation() {
        var now = Instant.now();
        jdbc.sql("""
                insert into conversation
                    (id, tenant_id, channel, provider_conversation_id, state, version, created_at, updated_at,
                     provider_account_id, participant_id)
                values (:id, :tenantId, 'WHATSAPP', '5214521234567', 'BOT_ACTIVE', 0, :now, :now,
                        'phone-number-1', '5214521234567')
                """)
                .param("id", CONVERSATION_ID)
                .param("tenantId", TENANT_ID)
                .param("now", Timestamp.from(now))
                .update();
    }
}
