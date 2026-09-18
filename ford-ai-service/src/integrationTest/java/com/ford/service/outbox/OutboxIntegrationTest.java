package com.ford.service.outbox;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
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
class OutboxIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    static JdbcOutboxStore store;

    @BeforeAll
    static void prepareSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        store = new JdbcOutboxStore(JdbcClient.create(dataSource));
    }

    @Test
    void claimsPendingEventAndPersistsDeliveryState() {
        var event = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "handoff", UUID.randomUUID(),
                "HANDOFF_CREATED", "{\"reason\":\"HOT_LEAD\"}", OutboxEvent.Status.PENDING,
                0, Instant.now().minusSeconds(1), null);
        store.append(event);

        var claimed = store.claimNext(Instant.now()).orElseThrow();
        store.markDelivered(claimed.id(), "provider-1");

        assertThat(JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .sql("select status, attempts from outbox_event where id = :id")
                .param("id", event.id())
                .query((rs, rowNum) -> rs.getString("status") + ":" + rs.getInt("attempts"))
                .single()).isEqualTo("DELIVERED:1");
    }
}
