package com.ford.service.leads;

import com.ford.service.leads.adapter.out.JdbcLeadJourneyRepository;
import com.ford.service.leads.domain.LeadJourneyFact;
import com.ford.service.leads.domain.LeadJourneyFactKey;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import com.ford.service.leads.domain.LeadJourneyTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = "spring.modulith.runtime.verification-enabled=false")
@ActiveProfiles("test")
class LeadJourneyRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcClient jdbc;
    @Autowired JdbcLeadJourneyRepository repository;
    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from lead_journey").update();
        jdbc.sql("delete from lead").update();
        jdbc.sql("delete from conversation").update();
    }

    @Test
    void lazilyCreatesConversationScopedJourneyWithoutCreatingCrmLead() {
        var ids = insertConversation();

        var journey = repository.getOrCreate(ids.tenantId(), ids.conversationId());

        assertThat(journey.currentTopic()).isNull();
        assertThat(journey.leadId()).isNull();
        assertThat(jdbc.sql("select count(*) from lead where conversation_id = :id")
                .param("id", ids.conversationId()).query(Integer.class).single()).isZero();
    }

    @Test
    void refusesReadsOutsideTheConversationTenant() {
        var ids = insertConversation();

        assertThatThrownBy(() -> repository.getOrCreate(UUID.randomUUID(), ids.conversationId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linksOnlyExistingLeadForTheSameConversationAndTenant() {
        var ids = insertConversation();
        var leadId = UUID.randomUUID();
        jdbc.sql("""
                insert into lead (id, tenant_id, conversation_id, created_at, updated_at)
                values (:lead, :tenant, :conversation, :now, :now)
                """).param("lead", leadId).param("tenant", ids.tenantId())
                .param("conversation", ids.conversationId()).param("now", Timestamp.from(Instant.now())).update();

        assertThat(repository.getOrCreate(ids.tenantId(), ids.conversationId()).leadId()).isEqualTo(leadId);
    }

    @Test
    void roundTripsJourneyAndFactProvenanceWithOptimisticVersionAndInboundIdempotency() {
        var ids = insertConversation();
        var initial = repository.getOrCreate(ids.tenantId(), ids.conversationId());
        var now = Instant.parse("2026-09-25T12:00:00Z");
        var update = new LeadJourneySnapshot(ids.tenantId(), ids.conversationId(), null,
                LeadJourneyTopic.PROMOTIONS_PRICING,
                Set.of(LeadJourneyTopic.PROMOTIONS_PRICING), Set.of(LeadJourneyTopic.VEHICLE_DISCOVERY),
                Map.of(LeadJourneyFactKey.VEHICLE_MODEL, new LeadJourneyFact("Ranger", "provider-123", now)),
                "ask_budget", 1, "provider-123", initial.version(), now);

        assertThat(repository.update(ids.tenantId(), ids.conversationId(), initial.version(), update, "provider-123"))
                .isTrue();
        var loaded = repository.getOrCreate(ids.tenantId(), ids.conversationId());

        assertThat(loaded.version()).isEqualTo(initial.version() + 1);
        assertThat(loaded.visitedTopics()).contains(LeadJourneyTopic.PROMOTIONS_PRICING);
        assertThat(loaded.pausedTopics()).contains(LeadJourneyTopic.VEHICLE_DISCOVERY);
        assertThat(loaded.facts().get(LeadJourneyFactKey.VEHICLE_MODEL))
                .isEqualTo(new LeadJourneyFact("Ranger", "provider-123", now));
        assertThat(loaded.lastProcessedMessageId()).isEqualTo("provider-123");
        assertThat(repository.update(ids.tenantId(), ids.conversationId(), initial.version(), update, "provider-124"))
                .isFalse();
        assertThat(repository.update(ids.tenantId(), ids.conversationId(), loaded.version(), loaded, "provider-123"))
                .isFalse();
    }

    private ConversationIds insertConversation() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var now = Instant.now();
        jdbc.sql("""
                insert into conversation (id, tenant_id, channel, provider_conversation_id, state, version,
                                          created_at, updated_at, provider_account_id, participant_id)
                values (:id, :tenant, 'WHATSAPP', :provider, 'BOT_ACTIVE', 0, :now, :now, 'provider-account', :participant)
                """).param("id", conversationId).param("tenant", tenantId)
                .param("provider", conversationId.toString()).param("participant", conversationId.toString())
                .param("now", Timestamp.from(now)).update();
        return new ConversationIds(tenantId, conversationId);
    }

    private record ConversationIds(UUID tenantId, UUID conversationId) { }
}
