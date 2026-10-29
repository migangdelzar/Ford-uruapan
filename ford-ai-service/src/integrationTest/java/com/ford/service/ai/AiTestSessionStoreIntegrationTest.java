package com.ford.service.ai;

import com.ford.service.ai.adapter.out.persistence.JdbcAiTestSessionStore;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AiTestSessionStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();
    static JdbcClient jdbc;
    static JdbcAiTestSessionStore store;

    @BeforeAll
    static void prepareSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcAiTestSessionStore(jdbc);
    }

    @Test
    void keepsRecentTurnsAfterAStoreIsRecreated() {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        store.saveTurn(tenant, "operator-a", conversation, "I need an SUV", "How many seats?");
        var restartedStore = new JdbcAiTestSessionStore(jdbc);

        assertThat(restartedStore.recentMessages(tenant, "operator-a", conversation, 8))
                .extracting(message -> message.role() + ":" + message.content())
                .containsExactly("user:I need an SUV", "assistant:How many seats?");
    }

    @Test
    void preventsAnotherTenantOrOperatorFromReadingTheSession() {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        store.saveTurn(tenant, "operator-a", conversation, "hello", "hi");

        assertThatThrownBy(() -> store.recentMessages(tenant, "operator-b", conversation, 8))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> store.recentMessages(UUID.randomUUID(), "operator-a", conversation, 8))
                .isInstanceOf(AccessDeniedException.class);
    }
}
