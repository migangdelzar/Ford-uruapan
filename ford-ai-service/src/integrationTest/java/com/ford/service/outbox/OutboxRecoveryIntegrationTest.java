package com.ford.service.outbox;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OutboxRecoveryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    static JdbcOutboxStore store;
    static JdbcClient jdbc;

    @BeforeEach
    void clearOutbox() {
        jdbc.sql("delete from outbox_event").update();
    }

    @BeforeAll
    static void prepareSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcOutboxStore(jdbc);
    }

    @Test
    void deduplicatesRepeatedStableBusinessKey() {
        var tenantId = UUID.randomUUID();
        var key = "tenant:conversation:reply-1";
        store.append(event(tenantId, UUID.randomUUID(), key, Instant.now().minusSeconds(1)));
        store.append(event(tenantId, UUID.randomUUID(), key, Instant.now().minusSeconds(1)));

        assertThat(jdbc.sql("select count(*) from outbox_event where tenant_id = :tenantId and idempotency_key = :key")
                .param("tenantId", tenantId).param("key", key).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void reclaimsExpiredLeaseAndAllowsSecondClaim() {
        var now = Instant.parse("2026-09-19T15:00:00Z");
        var event = event(UUID.randomUUID(), UUID.randomUUID(), "lease:" + UUID.randomUUID(), now.minusSeconds(1));
        store.append(event);

        var firstClaim = store.claimNext(now, Duration.ofSeconds(30)).orElseThrow();
        assertThat(firstClaim.leaseUntil()).isEqualTo(now.plusSeconds(30));
        assertThat(store.reclaimExpired(now.plusSeconds(31))).isEqualTo(1);

        var secondClaim = store.claimNext(now.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();
        assertThat(secondClaim.id()).isEqualTo(event.id());
        assertThat(secondClaim.attempts()).isEqualTo(2);
    }

    @Test
    void persistsProviderDeliveryIdAndIdempotencyKey() {
        var event = event(UUID.randomUUID(), UUID.randomUUID(), "provider:" + UUID.randomUUID(), Instant.now().minusSeconds(1));
        store.append(event);
        var claimed = store.claimNext(Instant.now(), Duration.ofSeconds(30)).orElseThrow();
        store.markDelivered(claimed.id(), "wamid.provider-1", Instant.now());

        var row = jdbc.sql("select status, idempotency_key, provider_delivery_id from outbox_event where id = :id")
                .param("id", event.id())
                .query((rs, rowNum) -> rs.getString("status") + ":" + rs.getString("idempotency_key") + ":"
                        + rs.getString("provider_delivery_id"))
                .single();
        assertThat(row).isEqualTo("DELIVERED:" + event.idempotencyKey() + ":wamid.provider-1");
    }

    @Test
    void staleWorkerCannotOverwriteAReclaimedLease() {
        var now = Instant.parse("2026-09-19T15:00:00Z");
        var event = event(UUID.randomUUID(), UUID.randomUUID(), "race:" + UUID.randomUUID(), now.minusSeconds(1));
        store.append(event);
        var firstClaim = store.claimNext(now, Duration.ofSeconds(30)).orElseThrow();
        store.reclaimExpired(now.plusSeconds(31));
        var secondClaim = store.claimNext(now.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();

        store.markDelivered(event.id(), "stale-provider", now.plusSeconds(32), firstClaim.leaseUntil());

        var status = jdbc.sql("select status, provider_delivery_id from outbox_event where id = :id")
                .param("id", event.id())
                .query((rs, rowNum) -> rs.getString("status") + ":" + rs.getString("provider_delivery_id"))
                .single();
        assertThat(status).isEqualTo("PROCESSING:null");

        store.markDelivered(event.id(), "current-provider", now.plusSeconds(32), secondClaim.leaseUntil());
        assertThat(jdbc.sql("select status, provider_delivery_id from outbox_event where id = :id")
                .param("id", event.id())
                .query((rs, rowNum) -> rs.getString("status") + ":" + rs.getString("provider_delivery_id"))
                .single()).isEqualTo("DELIVERED:current-provider");
    }

    private static OutboxEvent event(UUID tenantId, UUID aggregateId, String key, Instant availableAt) {
        return new OutboxEvent(UUID.randomUUID(), tenantId, "conversation", aggregateId,
                "OUTBOUND_DELIVERY_REQUESTED", "{}", OutboxEvent.Status.PENDING, 0,
                availableAt, null, null, key, null, null);
    }
}
