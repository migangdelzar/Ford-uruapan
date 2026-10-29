package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.out.JdbcConversationRepository;
import com.ford.service.conversations.application.ConversationPublicationRecoveryPolicy;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.modulith.events.EventPublication;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class ConversationPublicationRecoveryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OTHER_TENANT_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static JdbcClient jdbc;
    private static ConversationPublicationRecoveryPolicy policy;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        jdbc = JdbcClient.create(dataSource);
        policy = new ConversationPublicationRecoveryPolicy(
                new JdbcConversationRepository(jdbc, new ObjectMapper()));
    }

    @AfterAll
    static void cleanUp() {
        if (jdbc != null) {
            jdbc.sql("delete from conversation where id = :id").param("id", CONVERSATION_ID).update();
        }
    }

    @Test
    void allowsOnlyTenantScopedExistingConversationAndLeavesOrphanIneligible() {
        var now = Instant.now();
        jdbc.sql("""
                insert into conversation
                    (id, tenant_id, channel, provider_conversation_id, state, version, created_at, updated_at,
                     provider_account_id, participant_id)
                values (:id, :tenantId, 'WHATSAPP', 'synthetic-provider-conversation', 'BOT_ACTIVE', 0, :now, :now,
                        'synthetic-account', 'synthetic-participant')
                """)
                .param("id", CONVERSATION_ID)
                .param("tenantId", TENANT_ID)
                .param("now", Timestamp.from(now))
                .update();

        var retryBefore = now.plusSeconds(1);
        var valid = publication(new InboundMessageAccepted(UUID.randomUUID(), TENANT_ID, CONVERSATION_ID,
                Channel.WHATSAPP, "synthetic-valid", now.minusSeconds(1)), now.minusSeconds(10));
        var crossTenant = publication(new InboundMessageAccepted(UUID.randomUUID(), OTHER_TENANT_ID,
                CONVERSATION_ID, Channel.WHATSAPP, "synthetic-cross-tenant", now.minusSeconds(1)),
                now.minusSeconds(10));
        var orphan = publication(new InboundMessageAccepted(UUID.randomUUID(), TENANT_ID, UUID.randomUUID(),
                Channel.WHATSAPP, "synthetic-orphan", now.minusSeconds(1)), now.minusSeconds(10));

        assertThat(policy.eligible(valid, retryBefore)).isTrue();
        assertThat(policy.eligible(crossTenant, retryBefore)).isFalse();
        assertThat(policy.eligible(orphan, retryBefore)).isFalse();
        assertThat(jdbc.sql("select count(*) from conversation where id = :id")
                .param("id", CONVERSATION_ID).query(Long.class).single()).isEqualTo(1);
    }

    private static EventPublication publication(Object event, Instant publishedAt) {
        var publication = mock(EventPublication.class);
        when(publication.getStatus()).thenReturn(EventPublication.Status.PUBLISHED);
        when(publication.getPublicationDate()).thenReturn(publishedAt);
        when(publication.getCompletionDate()).thenReturn(Optional.empty());
        when(publication.getCompletionAttempts()).thenReturn(1);
        when(publication.getEvent()).thenReturn(event);
        return publication;
    }
}
