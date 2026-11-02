package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.adapter.out.JdbcConversationRuntimeSettingsRepository;
import com.ford.service.conversations.application.ConversationRuntimeSettings;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ConversationRuntimeSettingsPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();
    static JdbcConversationRuntimeSettingsRepository repository;
    static JdbcClient jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        jdbc = JdbcClient.create(dataSource);
        repository = new JdbcConversationRuntimeSettingsRepository(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void storesValidatedTenantSettingsWithAuditRevisionAndRejectsStaleWrites() {
        var tenantId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var first = repository.update(tenantId, actorId, 0, new ConversationRuntimeSettings(true, true, 12));

        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.settings().humanHandoffEnabled()).isTrue();
        assertThat(first.updatedBy()).isEqualTo(actorId);
        assertThat(repository.find(tenantId)).contains(first);
        assertThatThrownBy(() -> repository.update(tenantId, actorId, 0,
                new ConversationRuntimeSettings(false, false, 8)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");
        assertThatThrownBy(() -> repository.update(UUID.randomUUID(), actorId, 3,
                new ConversationRuntimeSettings(false, false, 8)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");

        var json = jdbc.sql("select config_json::text from tenant_runtime_configuration where tenant_id = :tenantId")
                .param("tenantId", tenantId).query(String.class).single();
        assertThat(json).contains("schemaVersion", "humanHandoffEnabled");
    }
}
