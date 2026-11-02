package com.ford.service.database;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SchemaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();

    @Test
    void liquibaseCreatesCanonicalFordTables() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=ford_metadata,ford_catalog",
                POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("alter extension pg_trgm set schema public");
        }
        new FordMetadataLiquibaseBootstrap(dataSource).initialize();
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.setLiquibaseSchema("ford_metadata");
        liquibase.setDefaultSchema("ford_metadata");
        liquibase.afterPropertiesSet();
        var appliedChangesetCount = scalar(dataSource,
                "select count(*) from ford_metadata.databasechangelog");
        var secondLiquibase = new SpringLiquibase();
        secondLiquibase.setDataSource(dataSource);
        secondLiquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        secondLiquibase.setLiquibaseSchema("ford_metadata");
        secondLiquibase.setDefaultSchema("ford_metadata");
        secondLiquibase.afterPropertiesSet();
        assertThat(scalar(dataSource, "select count(*) from ford_metadata.databasechangelog"))
                .isEqualTo(appliedChangesetCount);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableExists(connection, "ford_metadata", "vehicle_model")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "conversation_message")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "knowledge_chunk")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "outbox_event")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "channel_delivery")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "channel_message")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "event_publication")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "ai_system_prompt")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "conversation_memory_embedding")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "vector_store")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "lg4jcheckpoint")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "lg4jthread")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "lg4jthreadtag")).isTrue();
            assertThat(schemaExists(connection, "ford_graph")).isFalse();
            assertThat(columnExists(connection, "ford_metadata", "conversation_memory_embedding", "embedding")).isTrue();
            assertThat(columnFormat(connection, "ford_metadata", "vector_store", "embedding"))
                    .isEqualTo("vector(1536)");
            assertThat(indexExists(connection, "ford_metadata", "idx_conversation_memory_embedding_scope")).isTrue();
            assertThat(indexExists(connection, "ford_metadata", "idx_conversation_memory_embedding_hnsw")).isTrue();
            assertThat(indexExists(connection, "ford_metadata", "spring_ai_vector_index")).isTrue();
            assertThat(tableExists(connection, "public", "conversation_message")).isFalse();
            assertThat(tableExists(connection, "public", "vector_store")).isFalse();
            assertThat(extensionSchema(connection, "vector")).isEqualTo("ford_catalog");
            assertThat(extensionSchema(connection, "pg_trgm")).isEqualTo("ford_catalog");
            assertThat(schemaExists(connection, "public")).isTrue();
            assertThat(publicExtensions(connection)).isEmpty();
            assertThat(publicObjectCount(connection)).isZero();
            assertThat(operatorClassExists(connection, "ford_catalog", "vector_cosine_ops")).isTrue();
            assertThat(operatorClassExists(connection, "ford_catalog", "gin_trgm_ops")).isTrue();
            assertThat(tableExists(connection, "ford_metadata", "databasechangelog")).isTrue();
            assertThat(tableExists(connection, "public", "databasechangelog")).isFalse();
            assertThat(tableExists(connection, "ford_catalog", "vehicle_search_v1")).isTrue();
            assertThat(tableExists(connection, "ford_catalog", "vehicle_variants_v2")).isTrue();
            assertThat(tableExists(connection, "ford_catalog", "vehicle_facts_v2")).isTrue();
            assertThat(tableExists(connection, "ford_catalog", "comparison_facts_v2")).isTrue();
            assertThat(tableExists(connection, "ford_catalog", "offers_v2")).isTrue();
            assertThat(tableExists(connection, "ford_catalog", "catalog_documents_v1")).isTrue();
            assertThat(tableExists(connection, "ford_catalog", "page_links_v1")).isTrue();
            assertThat(tableExists(connection, "ford_catalog", "vehicle_facts_v1")).isTrue();
            assertThat(tableExists(connection, "ford_catalog", "vehicle_images_v1")).isTrue();
            assertThat(tableExists(connection, "ford_catalog", "price_import_journal")).isTrue();
            assertThat(tableExists(connection, "ford_catalog", "retrieval_document_manifest")).isTrue();
            assertThat(tableExists(connection, "ford_catalog", "reviewed_fact_manifest")).isTrue();
            assertThat(viewExists(connection, "ford_catalog", "catalog_pages_v1")).isTrue();
            assertThat(viewExists(connection, "ford_catalog", "catalog_status_v1")).isTrue();
            assertThat(viewExists(connection, "ford_catalog", "document_provenance_v1")).isTrue();
            assertThat(viewExists(connection, "ford_catalog", "promotions_v3")).isTrue();
            assertThat(scalar(dataSource, "select count(*) from ford_catalog.catalog_documents_v1"))
                    .as("empty catalog metadata baseline").isZero();
            try (var status = connection.createStatement().executeQuery(
                    "select * from ford_catalog.catalog_status_v1")) {
                assertThat(status.next()).isTrue();
                for (var countColumn : java.util.List.of("model_count", "active_model_year_count", "variant_count",
                        "page_count", "document_count", "embedded_document_count", "pending_embedding_count")) {
                    assertThat(status.getLong(countColumn)).as(countColumn).isZero();
                }
                assertThat(status.getTimestamp("latest_page_crawl_at")).isNull();
                assertThat(status.next()).isFalse();
            }
            assertThat(columnFormat(connection, "catalog_documents_v1", "embedding"))
                    .isEqualTo("vector(1024)");
            assertThat(columnFormat(connection, "catalog_documents_v1", "search_vector"))
                    .isEqualTo("tsvector");
            assertThat(privilege(connection, "ford_catalog_reader", "ford_catalog.vehicle_search_v1", "SELECT")).isTrue();
            assertThat(privilege(connection, "ford_catalog_reader", "ford_catalog.catalog_documents_v1", "SELECT")).isTrue();
            var readableRelations = java.util.List.of(
                    "vehicle_search_v1", "vehicle_variants_v2", "vehicle_facts_v2", "comparison_facts_v2",
                    "offers_v2", "catalog_documents_v1", "catalog_pages_v1", "catalog_status_v1", "promotions_v3");
            for (var relation : readableRelations) {
                assertThat(privilege(connection, "ford_catalog_reader", "ford_catalog." + relation, "SELECT"))
                        .as("reader SELECT on explicit approved relation %s", relation).isTrue();
                try (var reader = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl() + "&currentSchema=ford_catalog", "ford_catalog_reader", "integration-test-only");
                     var statement = reader.createStatement()) {
                    statement.executeQuery("select * from ford_catalog." + relation + " limit 0").close();
                }
                for (var writePrivilege : java.util.List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                    assertThat(privilege(connection, "ford_catalog_reader", "ford_catalog." + relation, writePrivilege))
                            .as("reader %s denied on %s", writePrivilege, relation).isFalse();
                }
            }
            assertThat(readerRoleFlags(connection)).containsExactly(true, false, false, false, false, false);
            assertThat(scalar(dataSource, "select count(*) from pg_auth_members m join pg_roles r on r.oid = m.member where r.rolname = 'ford_catalog_reader'"))
                    .as("reader has no role memberships").isZero();
            assertThat(privilege(connection, "ford_catalog_reader", "ford_catalog.retrieval_document_manifest", "SELECT")).isFalse();
            assertThat(privilege(connection, "ford_catalog_reader", "ford_catalog.price_import_journal", "SELECT")).isFalse();
            assertThat(privilege(connection, "ford_catalog_reader", "ford_catalog.reviewed_fact_manifest", "SELECT")).isFalse();
            assertThat(privilege(connection, "ford_catalog_reader", "ford_catalog.document_provenance_v1", "SELECT")).isFalse();
            for (var relation : java.util.List.of("vehicle_images_v1", "vehicle_facts_v1", "page_links_v1")) {
                assertThat(privilege(connection, "ford_catalog_reader", "ford_catalog." + relation, "SELECT"))
                        .as("reader cannot access unapproved base table %s", relation).isFalse();
            }
            assertThat(schemaPrivilege(connection, "ford_catalog_reader", "ford_metadata", "USAGE")).isFalse();
        }

        var readerUrl = POSTGRES.getJdbcUrl() + "&currentSchema=ford_catalog";
        try (var reader = java.sql.DriverManager.getConnection(readerUrl,
                "ford_catalog_reader", "integration-test-only");
             var statement = reader.createStatement()) {
            try (var result = statement.executeQuery("select count(*) from ford_catalog.vehicle_search_v1")) {
                result.next();
                assertThat(result.getLong(1)).isZero();
            }
            assertThatThrownBy(() -> statement.executeQuery("select * from ford_catalog.retrieval_document_manifest"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeQuery("select * from ford_catalog.price_import_journal"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeQuery("select * from ford_catalog.reviewed_fact_manifest"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeQuery("select * from ford_catalog.document_provenance_v1"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeQuery("select * from ford_metadata.conversation"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "insert into ford_catalog.vehicle_search_v1 (model, year) values ('TEST', 2026)"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    private static boolean tableExists(Connection connection, String schema, String tableName) throws Exception {
        try (var statement = connection.prepareStatement(
                "select exists (select 1 from information_schema.tables "
                        + "where table_schema = ? and table_name = ?)")) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static boolean columnExists(Connection connection, String schema, String tableName, String columnName) throws Exception {
        try (var statement = connection.prepareStatement(
                "select exists (select 1 from information_schema.columns "
                        + "where table_schema = ? and table_name = ? and column_name = ?)")) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            statement.setString(3, columnName);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static boolean indexExists(Connection connection, String schema, String indexName) throws Exception {
        try (var statement = connection.prepareStatement(
                "select exists (select 1 from pg_indexes "
                        + "where schemaname = ? and indexname = ?)")) {
            statement.setString(1, schema);
            statement.setString(2, indexName);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static boolean viewExists(Connection connection, String schema, String viewName) throws Exception {
        try (var statement = connection.prepareStatement(
                "select exists (select 1 from information_schema.views where table_schema = ? and table_name = ?)")) {
            statement.setString(1, schema);
            statement.setString(2, viewName);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static boolean schemaExists(Connection connection, String schema) throws Exception {
        try (var statement = connection.prepareStatement(
                "select exists (select 1 from information_schema.schemata where schema_name = ?)")) {
            statement.setString(1, schema);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static String extensionSchema(Connection connection, String extension) throws Exception {
        try (var statement = connection.prepareStatement("""
                select n.nspname
                from pg_extension e join pg_namespace n on n.oid = e.extnamespace
                where e.extname = ?
                """)) {
            statement.setString(1, extension);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static java.util.List<String> publicExtensions(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("""
                select e.extname
                from pg_extension e join pg_namespace n on n.oid = e.extnamespace
                where n.nspname = 'public'
                order by e.extname
                """)) {
            try (var result = statement.executeQuery()) {
                var extensions = new java.util.ArrayList<String>();
                while (result.next()) {
                    extensions.add(result.getString(1));
                }
                return extensions;
            }
        }
    }

    private static long publicObjectCount(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("""
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

    private static boolean operatorClassExists(Connection connection, String schema, String operatorClass)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                select exists (
                    select 1 from pg_opclass c join pg_namespace n on n.oid = c.opcnamespace
                    where n.nspname = ? and c.opcname = ?
                )
                """)) {
            statement.setString(1, schema);
            statement.setString(2, operatorClass);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static String columnFormat(Connection connection, String tableName, String columnName) throws Exception {
        try (var statement = connection.prepareStatement("""
                select format_type(a.atttypid, a.atttypmod)
                from pg_attribute a
                join pg_class c on c.oid = a.attrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'ford_catalog' and c.relname = ?
                  and a.attname = ? and not a.attisdropped
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static String columnFormat(Connection connection, String schema, String tableName, String columnName)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                select format_type(a.atttypid, a.atttypmod)
                from pg_attribute a
                join pg_class c on c.oid = a.attrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and c.relname = ?
                  and a.attname = ? and not a.attisdropped
                """)) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            statement.setString(3, columnName);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static boolean privilege(Connection connection, String role, String relation, String privilege)
            throws Exception {
        try (var statement = connection.prepareStatement("select has_table_privilege(?, ?, ?)")) {
            statement.setString(1, role);
            statement.setString(2, relation);
            statement.setString(3, privilege);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static boolean schemaPrivilege(Connection connection, String role, String schema, String privilege)
            throws Exception {
        try (var statement = connection.prepareStatement("select has_schema_privilege(?, ?, ?)")) {
            statement.setString(1, role);
            statement.setString(2, schema);
            statement.setString(3, privilege);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static java.util.List<Boolean> readerRoleFlags(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("""
                select rolcanlogin, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
                from pg_roles where rolname = 'ford_catalog_reader'
                """)) {
            try (var result = statement.executeQuery()) {
                result.next();
                return java.util.List.of(result.getBoolean(1), result.getBoolean(2), result.getBoolean(3),
                        result.getBoolean(4), result.getBoolean(5), result.getBoolean(6));
            }
        }
    }

    private static long scalar(javax.sql.DataSource dataSource, String sql) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
