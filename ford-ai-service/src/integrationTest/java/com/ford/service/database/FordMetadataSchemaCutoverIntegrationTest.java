package com.ford.service.database;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FordMetadataSchemaCutoverIntegrationTest {

    @Container
    static final FordPostgresContainer POSTGRES = new FordPostgresContainer();

    private JdbcClient jdbc;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void seedLegacySchemas() {
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.sql("alter extension vector set schema public").update();
        jdbc.sql("alter extension pg_trgm set schema public").update();
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("drop table if exists ford_metadata.databasechangelog").update();
        jdbc.sql("drop table if exists ford_metadata.databasechangeloglock").update();
        jdbc.sql("drop table if exists ford_metadata.conversation").update();
        jdbc.sql("drop table if exists ford_metadata.vector_store").update();
        jdbc.sql("drop table if exists ford_metadata.lg4jcheckpoint").update();
        jdbc.sql("drop view if exists ford_graph.unclassified_view").update();
        jdbc.sql("drop table if exists ford_graph.lg4jcheckpoint").update();
        jdbc.sql("drop table if exists ford.ai_execution").update();
        jdbc.sql("drop schema if exists ford").update();
        jdbc.sql("drop schema if exists ford_metadata").update();
        jdbc.sql("drop schema if exists ford_graph").update();
        jdbc.sql("drop table if exists public.conversation").update();
        jdbc.sql("drop table if exists public.vector_store").update();
        jdbc.sql("drop table if exists public.keep_for_review").update();
        jdbc.sql("create schema ford_metadata").update();
        jdbc.sql("create schema ford_graph").update();
        jdbc.sql("create table public.conversation (id uuid primary key, tenant_id uuid not null)").update();
        jdbc.sql("create table public.vector_store (id uuid primary key, content text, metadata json, embedding public.vector(3))").update();
        jdbc.sql("create table public.keep_for_review (value text not null)").update();
        jdbc.sql("create table ford_graph.lg4jcheckpoint (thread_id text, checkpoint_id text, saved_at timestamptz)").update();
        jdbc.sql("insert into public.conversation values ('15000000-0000-0000-0000-000000000001', '25000000-0000-0000-0000-000000000001')").update();
        jdbc.sql("insert into public.vector_store values ('35000000-0000-0000-0000-000000000001', 'approved agency policy', '{\"tenantId\":\"25000000-0000-0000-0000-000000000001\"}', '[1,0,0]')").update();
        jdbc.sql("insert into ford_graph.lg4jcheckpoint values ('conversation-1', 'checkpoint-1', current_timestamp)").update();
        jdbc.sql("insert into public.keep_for_review values ('untouched')").update();
    }

    @Test
    void movesOnlyKnownServiceAndCheckpointTablesWithoutChangingRows() throws Exception {
        jdbc.sql("alter extension vector set schema ford_metadata").update();

        applyCutover();

        assertThat(tableExists("ford_metadata", "conversation")).isTrue();
        assertThat(tableExists("ford_metadata", "vector_store")).isTrue();
        assertThat(tableExists("ford_metadata", "lg4jcheckpoint")).isTrue();
        assertThat(tableExists("public", "conversation")).isFalse();
        assertThat(tableExists("public", "vector_store")).isFalse();
        assertThat(tableExists("public", "keep_for_review")).isTrue();
        assertThat(schemaExists("ford_graph")).isFalse();
        assertThat(extensionSchema("vector")).isEqualTo("public");
        assertThat(jdbc.sql("select count(*) from ford_metadata.conversation").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select content from ford_metadata.vector_store").query(String.class).single())
                .isEqualTo("approved agency policy");
        assertThat(jdbc.sql("select checkpoint_id from ford_metadata.lg4jcheckpoint")
                .query(String.class).single()).isEqualTo("checkpoint-1");
        assertThat(jdbc.sql("select value from public.keep_for_review").query(String.class).single())
                .isEqualTo("untouched");
    }

    @Test
    void refusesToDropGraphSchemaWhenUnknownObjectsRemain() {
        jdbc.sql("create view ford_graph.unclassified_view as select 1 as value").update();

        assertThatThrownBy(this::applyCutover)
                .isInstanceOf(Exception.class);

        assertThat(tableExists("public", "conversation")).isTrue();
        assertThat(tableExists("ford_metadata", "conversation")).isFalse();
        assertThat(schemaExists("ford_graph")).isTrue();
    }

    @Test
    void refusesDuplicateOperationalTableInFordAndPublicWithoutMovingRows() {
        jdbc.sql("create schema ford").update();
        jdbc.sql("create table ford.ai_execution (id integer primary key, marker text not null)").update();
        jdbc.sql("create table public.ai_execution (id integer primary key, marker text not null)").update();
        jdbc.sql("insert into ford.ai_execution values (1, 'ford-copy')").update();
        jdbc.sql("insert into public.ai_execution values (1, 'public-copy')").update();

        assertThatThrownBy(this::applyCutover)
                .hasStackTraceContaining("Relation ai_execution exists in an unreviewed schema");

        assertThat(tableExists("ford", "ai_execution")).isTrue();
        assertThat(tableExists("public", "ai_execution")).isTrue();
        assertThat(tableExists("ford_metadata", "ai_execution")).isFalse();
        assertThat(jdbc.sql("select marker from ford.ai_execution where id = 1")
                .query(String.class).single()).isEqualTo("ford-copy");
        assertThat(jdbc.sql("select marker from public.ai_execution where id = 1")
                .query(String.class).single()).isEqualTo("public-copy");
        assertThat(schemaExists("public")).isTrue();
    }

    private void applyCutover() throws Exception {
        new FordMetadataLiquibaseBootstrap(dataSource).initialize();
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/changes/025-ford-metadata-schema.yaml");
        liquibase.setDefaultSchema("ford_metadata");
        liquibase.setLiquibaseSchema("ford_metadata");
        liquibase.afterPropertiesSet();
    }

    private boolean tableExists(String schema, String table) {
        return jdbc.sql("select exists (select 1 from information_schema.tables where table_schema = :schema and table_name = :table)")
                .param("schema", schema).param("table", table).query(Boolean.class).single();
    }

    private boolean schemaExists(String schema) {
        return jdbc.sql("select exists (select 1 from information_schema.schemata where schema_name = :schema)")
                .param("schema", schema).query(Boolean.class).single();
    }

    private String extensionSchema(String extension) {
        return jdbc.sql("select n.nspname from pg_extension e join pg_namespace n on n.oid = e.extnamespace where e.extname = :extension")
                .param("extension", extension).query(String.class).single();
    }
}
