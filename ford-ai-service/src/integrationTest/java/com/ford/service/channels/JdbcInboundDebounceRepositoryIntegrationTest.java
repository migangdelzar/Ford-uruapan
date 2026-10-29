package com.ford.service.channels;

import com.ford.service.channels.adapter.out.JdbcInboundDebounceRepository;
import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.domain.Channel;
import com.ford.service.database.FordPostgresContainer;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcInboundDebounceRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-17T18:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();

    static JdbcClient jdbc;
    static JdbcInboundDebounceRepository repository;

    @BeforeAll
    static void prepareSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        jdbc = JdbcClient.create(dataSource);
        repository = new JdbcInboundDebounceRepository(jdbc);
    }

    @BeforeEach
    void isolateScheduleState() {
        jdbc.sql("delete from inbound_debounce").update();
    }

    @Test
    void burstCoalescesIntoTheEarliestDueTimeAndLatestReceipt() {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var first = event(tenant, conversation, "wamid.1");
        var second = event(tenant, conversation, "wamid.2");

        repository.schedule(first, NOW.plusSeconds(5));
        repository.schedule(second, NOW.plusSeconds(1));

        assertThat(rowCount(tenant, conversation)).isEqualTo(1);
        assertThat(repository.isDue(tenant, conversation, second.receiptId(), NOW)).isFalse();
        assertThat(repository.isDue(tenant, conversation, second.receiptId(), NOW.plusSeconds(1))).isTrue();
        assertThat(dueAt(tenant, conversation)).isEqualTo(NOW.plusSeconds(1));
        assertThat(receiptId(tenant, conversation)).isEqualTo(second.receiptId());
    }

    @Test
    void retriedDeliveryOfTheSameReceiptDoesNotCreateASecondRow() {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var event = event(tenant, conversation, "wamid.1");

        repository.schedule(event, NOW.plusSeconds(5));
        repository.schedule(event, NOW.plusSeconds(5));

        assertThat(rowCount(tenant, conversation)).isEqualTo(1);
    }

    @Test
    void independentConversationsKeepIndependentSchedulesAndClaimOnce() {
        var first = event(UUID.randomUUID(), UUID.randomUUID(), "wamid.1");
        var second = event(UUID.randomUUID(), UUID.randomUUID(), "wamid.2");

        repository.schedule(first, NOW.plusSeconds(5));
        repository.schedule(second, NOW.plusSeconds(1));

        var claimed = repository.claimDue(NOW.plusSeconds(5), 10);
        assertThat(claimed).extracting(InboundMessageAccepted::providerMessageId)
                .containsExactlyInAnyOrder("wamid.1", "wamid.2");
        assertThat(repository.claimDue(NOW.plusSeconds(5), 10)).isEmpty();
    }

    private static long rowCount(UUID tenant, UUID conversation) {
        return jdbc.sql("select count(*) from inbound_debounce where tenant_id = :tenantId and conversation_id = :conversationId")
                .param("tenantId", tenant).param("conversationId", conversation).query(Long.class).single();
    }

    private static Instant dueAt(UUID tenant, UUID conversation) {
        return jdbc.sql("select due_at from inbound_debounce where tenant_id = :tenantId and conversation_id = :conversationId")
                .param("tenantId", tenant).param("conversationId", conversation)
                .query((rs, rowNum) -> rs.getTimestamp("due_at").toInstant()).single();
    }

    private static UUID receiptId(UUID tenant, UUID conversation) {
        return jdbc.sql("select receipt_id from inbound_debounce where tenant_id = :tenantId and conversation_id = :conversationId")
                .param("tenantId", tenant).param("conversationId", conversation).query(UUID.class).single();
    }

    private static InboundMessageAccepted event(UUID tenant, UUID conversation, String providerMessageId) {
        return new InboundMessageAccepted(UUID.randomUUID(), tenant, conversation,
                Channel.WHATSAPP, providerMessageId, NOW);
    }
}
