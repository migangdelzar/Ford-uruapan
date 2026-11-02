package com.ford.service.database;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FordEmptyDatabaseBootstrapIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordBarePostgresContainer();

    @Test
    void cleanBootstrapCreatesCanonicalSchemasAndLeavesAllBusinessRelationsEmpty() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=ford_metadata",
                POSTGRES.getUsername(), POSTGRES.getPassword());
        createReaderRole();

        try (var connection = dataSource.getConnection()) {
            assertThat(schemaExists(connection, "ford_metadata")).isFalse();
            assertThat(schemaExists(connection, "ford_catalog")).isFalse();
            assertThat(extensionExists(connection, "vector")).isFalse();
            assertThat(extensionExists(connection, "pg_trgm")).isFalse();
        }

        new FordMetadataLiquibaseBootstrap(dataSource).initialize();
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.setLiquibaseSchema("ford_metadata");
        liquibase.setDefaultSchema("ford_metadata");
        liquibase.afterPropertiesSet();

        try (var connection = dataSource.getConnection()) {
            assertThat(schemaExists(connection, "ford_metadata")).isTrue();
            assertThat(schemaExists(connection, "ford_catalog")).isTrue();
            assertThat(schemaExists(connection, "public")).isTrue();
            assertThat(extensionSchema(connection, "vector")).isEqualTo("ford_catalog");
            assertThat(extensionSchema(connection, "pg_trgm")).isEqualTo("ford_catalog");
            assertThat(publicObjectCount(connection)).isZero();
            assertThat(liquibaseTrackingRelations(connection)).containsExactly(
                    "ford_metadata.databasechangelog", "ford_metadata.databasechangeloglock");

            for (var table : businessTables(connection)) {
                if (table.schema().equals("ford_metadata")
                        && List.of("databasechangelog", "databasechangeloglock").contains(table.name())) {
                    continue;
                }
                assertThat(rowCount(connection, table)).as("rows in %s.%s", table.schema(), table.name()).isZero();
            }
            for (var view : viewLikeRelations(connection, "ford_catalog")) {
                if (view.name().equals("catalog_status_v1")) {
                    assertCatalogStatusIsEmpty(connection);
                } else {
                    assertThat(rowCount(connection, view)).as("rows in view %s.%s", view.schema(), view.name()).isZero();
                }
            }
        }
    }

    private static void assertCatalogStatusIsEmpty(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("select * from ford_catalog.catalog_status_v1")) {
            assertThat(result.next()).isTrue();
            for (var countColumn : List.of("model_count", "active_model_year_count", "variant_count", "page_count",
                    "document_count", "embedded_document_count", "pending_embedding_count")) {
                assertThat(result.getLong(countColumn)).as(countColumn).isZero();
            }
            assertThat(result.getTimestamp("latest_page_crawl_at")).isNull();
            assertThat(result.getString("latest_sync_status")).isEqualTo("LOCAL_SNAPSHOT");
            assertThat(result.next()).isFalse();
        }
    }

    private static void createReaderRole() throws SQLException {
        try (var connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.execute("create role ford_catalog_reader login password 'integration-test-only'");
        }
    }

    private static boolean schemaExists(Connection connection, String schema) throws SQLException {
        try (var statement = connection.prepareStatement(
                "select exists (select 1 from information_schema.schemata where schema_name = ?)");) {
            statement.setString(1, schema);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static boolean extensionExists(Connection connection, String extension) throws SQLException {
        try (var statement = connection.prepareStatement("select exists (select 1 from pg_extension where extname = ?)");) {
            statement.setString(1, extension);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static String extensionSchema(Connection connection, String extension) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select n.nspname from pg_extension e
                join pg_namespace n on n.oid = e.extnamespace where e.extname = ?
                """)) {
            statement.setString(1, extension);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static long publicObjectCount(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                select
                    (select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public') +
                    (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public') +
                    (select count(*) from pg_type t join pg_namespace n on n.oid = t.typnamespace where n.nspname = 'public') +
                    (select count(*) from pg_operator o join pg_namespace n on n.oid = o.oprnamespace where n.nspname = 'public') +
                    (select count(*) from pg_opclass o join pg_namespace n on n.oid = o.opcnamespace where n.nspname = 'public') +
                    (select count(*) from pg_opfamily o join pg_namespace n on n.oid = o.opfnamespace where n.nspname = 'public') +
                    (select count(*) from pg_collation c join pg_namespace n on n.oid = c.collnamespace where n.nspname = 'public') +
                    (select count(*) from pg_conversion c join pg_namespace n on n.oid = c.connamespace where n.nspname = 'public')
                """)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static List<String> liquibaseTrackingRelations(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                select n.nspname || '.' || c.relname
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where c.relname in ('databasechangelog', 'databasechangeloglock') and c.relkind = 'r'
                  and n.nspname not in ('pg_catalog', 'information_schema')
                order by n.nspname, c.relname
                """)) {
            var relations = new ArrayList<String>();
            while (result.next()) relations.add(result.getString(1));
            return relations;
        }
    }

    private static List<Relation> businessTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                select n.nspname, c.relname from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where c.relkind in ('r', 'p') and n.nspname not in ('pg_catalog', 'information_schema')
                  and n.nspname not like 'pg\\_%' escape '\\'
                order by n.nspname, c.relname
                """)) {
            var relations = new ArrayList<Relation>();
            while (result.next()) relations.add(new Relation(result.getString(1), result.getString(2)));
            return relations;
        }
    }

    private static List<Relation> viewLikeRelations(Connection connection, String schema) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select n.nspname, c.relname from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and c.relkind in ('v', 'm')
                order by c.relname
                """)) {
            statement.setString(1, schema);
            try (var result = statement.executeQuery()) {
                var relations = new ArrayList<Relation>();
                while (result.next()) relations.add(new Relation(result.getString(1), result.getString(2)));
                return relations;
            }
        }
    }

    private static long rowCount(Connection connection, Relation relation) throws SQLException {
        var sql = "select count(*) from \"" + relation.schema().replace("\"", "\"\"") + "\".\""
                + relation.name().replace("\"", "\"\"") + "\"";
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private record Relation(String schema, String name) { }
}
