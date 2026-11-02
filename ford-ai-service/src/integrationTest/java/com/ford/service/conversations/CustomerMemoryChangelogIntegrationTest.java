package com.ford.service.conversations;

import com.ford.service.database.FordPostgresContainer;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerMemoryChangelogIntegrationTest {

    @Test
    void createsTenantScopedIdentityTablesWithoutRawParticipantIdentifiers() throws Exception {
        try (var postgres = new FordPostgresContainer()) {
            postgres.start();
            try (var connection = java.sql.DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                        new JdbcConnection(connection));
                var bootstrap = new Liquibase("db/changelog/changes/001-platform.yaml",
                        new ClassLoaderResourceAccessor(), database);
                bootstrap.update(new Contexts(), new LabelExpression());
                var delivery = new Liquibase("db/changelog/changes/002-channel-delivery.yaml",
                        new ClassLoaderResourceAccessor(), database);
                delivery.update(new Contexts(), new LabelExpression());
                var routing = new Liquibase("db/changelog/changes/003-channel-message.yaml",
                        new ClassLoaderResourceAccessor(), database);
                routing.update(new Contexts(), new LabelExpression());
                var memory = new Liquibase("db/changelog/changes/007-conversation-memory.yaml",
                        new ClassLoaderResourceAccessor(), database);
                memory.update(new Contexts(), new LabelExpression());
                var liquibase = new Liquibase("db/changelog/changes/028-customer-memory-profile.yaml",
                        new ClassLoaderResourceAccessor(), database);
                liquibase.update(new Contexts(), new LabelExpression());
                var columns = connection.getMetaData().getColumns(null, null,
                        "customer_memory_identity_fingerprint", null);
                var names = new java.util.HashSet<String>();
                while (columns.next()) names.add(columns.getString("COLUMN_NAME"));
                assertThat(names).contains("customer_identity_id", "tenant_id", "channel",
                        "key_version", "participant_fingerprint").doesNotContain("participant_id", "phone_number");
                liquibase.rollback(1, new Contexts(), new LabelExpression());
            }
        }
    }
}
