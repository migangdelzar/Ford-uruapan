package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.out.JdbcConversationRepository;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationTurn;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = "spring.modulith.runtime.verification-enabled=false")
@ActiveProfiles("test")
class ConversationRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

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
    JdbcConversationRepository repository;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from outbox_event").update();
        jdbc.sql("delete from conversation_message").update();
        jdbc.sql("delete from channel_message").update();
        jdbc.sql("delete from conversation").update();
    }

    @Test
    void completesTurnOnlyForExpectedVersionAndWritesReplyOutbox() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        insertConversation(tenantId, conversationId);

        var snapshot = repository.find(conversationId).orElseThrow();
        var completed = repository.completeBotTurn(
                conversationId, snapshot.version(), new ConversationTurn("Respuesta en español."));

        assertThat(completed).isTrue();
        assertThat(jdbc.sql("select version from conversation where id = :id")
                .param("id", conversationId).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("select count(*) from conversation_message where conversation_id = :id and direction = 'OUTBOUND'")
                .param("id", conversationId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id = :id and status = 'PENDING'")
                .param("id", conversationId).query(Integer.class).single()).isEqualTo(1);

        assertThat(repository.completeBotTurn(
                conversationId, snapshot.version(), new ConversationTurn("Respuesta obsoleta.")))
                .isFalse();
    }

    private void insertConversation(UUID tenantId, UUID conversationId) {
        var now = Instant.now();
        jdbc.sql("""
                insert into conversation
                    (id, tenant_id, channel, provider_conversation_id, state, version, created_at, updated_at,
                     provider_account_id, participant_id)
                values (:id, :tenantId, 'WHATSAPP', '5214521234567', 'BOT_ACTIVE', 0, :now, :now,
                        'phone-number-1', '5214521234567')
                """)
                .param("id", conversationId)
                .param("tenantId", tenantId)
                .param("now", Timestamp.from(now))
                .update();
        jdbc.sql("""
                insert into channel_message
                    (id, conversation_id, tenant_id, channel, provider_message_id, participant_id,
                     content, received_at, raw_payload_reference)
                values (:id, :conversationId, :tenantId, 'WHATSAPP', 'wamid.inbound', '5214521234567',
                        '¿Hay Ranger?', :receivedAt, 'test:payload')
                """)
                .param("id", UUID.randomUUID())
                .param("conversationId", conversationId)
                .param("tenantId", tenantId)
                .param("receivedAt", Timestamp.from(now))
                .update();
    }
}
