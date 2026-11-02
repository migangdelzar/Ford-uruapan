package com.ford.service.conversations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class LocalCatalogSnapshotImportCliTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ford_catalog");
    private static final String READER = "ford_catalog_cli_reader";

    @TempDir Path tempDir;
    private DriverManagerDataSource adminDataSource;

    @BeforeEach
    void setUp() throws Exception {
        adminDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var connection = adminDataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists ford_read cascade");
            try (var result = statement.executeQuery("select exists(select 1 from pg_roles where rolname='" + READER + "')")) {
                if (result.next() && result.getBoolean(1)) {
                    statement.execute("drop owned by " + READER);
                    statement.execute("drop role " + READER);
                }
            }
        }
    }

    @Test
    void refusesRemoteJdbcTargets() {
        assertThatThrownBy(() -> LocalCatalogSnapshotImportCli.validateTarget(
                "jdbc:postgresql://catalog.example:5432/ford_catalog"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void refusesJdbcQueryParametersThatCanOverrideTheValidatedHost() {
        assertThatThrownBy(() -> LocalCatalogSnapshotImportCli.validateTarget(
                "jdbc:postgresql://localhost:5432/ford_catalog?PGHOST=catalog.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void refusesAnyDatabaseOtherThanFordCatalog() {
        assertThatThrownBy(() -> LocalCatalogSnapshotImportCli.validateTarget(
                "jdbc:postgresql://localhost:5432/ford_service"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ford_catalog");
    }

    @Test
    void refusesInvalidLoopbackPorts() {
        assertThatThrownBy(() -> LocalCatalogSnapshotImportCli.validateTarget(
                "jdbc:postgresql://localhost:99999/ford_catalog"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void rejectsSyntheticSnapshotBeforeCreatingReaderRole() throws Exception {
        LocalCatalogSnapshotLoaderTest.writeSnapshot(tempDir);

        assertThatThrownBy(() -> LocalCatalogSnapshotImportCli.importSnapshot(
                tempDir, adminDataSource, targetUrl(), READER, "reader-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source label is not approved");

        assertThat(roleExists()).isFalse();
    }

    @Test
    void removesOnlyItsNewReaderRoleWhenImportFails() throws Exception {
        LocalCatalogSnapshotLoaderTest.writeSnapshot(tempDir, "dealer_information");
        LocalCatalogSnapshotLoaderTest.markSnapshotAsApprovedSource(tempDir);

        assertThatThrownBy(() -> LocalCatalogSnapshotImportCli.importSnapshot(
                tempDir, adminDataSource, targetUrl(), READER, "reader-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local catalog import failed")
                .hasMessageContaining("reader role was removed");

        assertThat(roleExists()).isFalse();
        assertThat(schemaExists()).isFalse();
    }

    @Test
    void refusesPreexistingReaderRoleWithoutDroppingIt() throws Exception {
        createReaderRole();
        LocalCatalogSnapshotLoaderTest.writeSnapshot(tempDir);
        LocalCatalogSnapshotLoaderTest.markSnapshotAsApprovedSource(tempDir);

        assertThatThrownBy(() -> LocalCatalogSnapshotImportCli.importSnapshot(
                tempDir, adminDataSource, targetUrl(), READER, "reader-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reader role already exists");

        assertThat(roleExists()).isTrue();
    }

    @Test
    void refusesDatasourceConnectedToDifferentDatabase() throws Exception {
        LocalCatalogSnapshotLoaderTest.writeSnapshot(tempDir);
        LocalCatalogSnapshotLoaderTest.markSnapshotAsApprovedSource(tempDir);
        var wrongDatabase = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl().replace("/ford_catalog", "/postgres"),
                POSTGRES.getUsername(), POSTGRES.getPassword());

        assertThatThrownBy(() -> LocalCatalogSnapshotImportCli.importSnapshot(
                tempDir, wrongDatabase, targetUrl(), READER, "reader-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ford_catalog");

        assertThat(roleExists()).isFalse();
    }

    @Test
    void importsApprovedSnapshotAndPersistsReaderRole() throws Exception {
        LocalCatalogSnapshotLoaderTest.writeSnapshot(tempDir);
        LocalCatalogSnapshotLoaderTest.markSnapshotAsApprovedSource(tempDir);

        var summary = LocalCatalogSnapshotImportCli.importSnapshot(
                tempDir, adminDataSource, targetUrl(), READER, "reader-secret");

        assertThat(summary.rowCounts()).containsEntry("vehicle-search.csv", 1);
        assertThat(roleExists()).isTrue();
    }

    private boolean roleExists() throws Exception {
        try (var connection = adminDataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("select exists(select 1 from pg_roles where rolname='" + READER + "')")) {
            return result.next() && result.getBoolean(1);
        }
    }

    private boolean schemaExists() throws Exception {
        try (var connection = adminDataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("select to_regnamespace('ford_read') is not null")) {
            return result.next() && result.getBoolean(1);
        }
    }

    private void createReaderRole() throws Exception {
        try (var connection = adminDataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create role " + READER + " login password 'preexisting-secret'");
        }
    }

    private String targetUrl() {
        return "jdbc:postgresql://localhost:" + POSTGRES.getFirstMappedPort() + "/ford_catalog";
    }
}
