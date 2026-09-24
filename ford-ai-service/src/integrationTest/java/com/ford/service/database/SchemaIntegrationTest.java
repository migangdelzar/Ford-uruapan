package com.ford.service.database;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Test
    void liquibaseCreatesCanonicalFordTables() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableExists(connection, "vehicle_model")).isTrue();
            assertThat(tableExists(connection, "conversation_message")).isTrue();
            assertThat(tableExists(connection, "knowledge_chunk")).isTrue();
            assertThat(tableExists(connection, "outbox_event")).isTrue();
            assertThat(tableExists(connection, "channel_delivery")).isTrue();
            assertThat(tableExists(connection, "channel_message")).isTrue();
            assertThat(tableExists(connection, "event_publication")).isTrue();
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws Exception {
        try (var statement = connection.prepareStatement(
                "select exists (select 1 from information_schema.tables "
                        + "where table_schema = 'public' and table_name = ?)")) {
            statement.setString(1, tableName);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }
}
