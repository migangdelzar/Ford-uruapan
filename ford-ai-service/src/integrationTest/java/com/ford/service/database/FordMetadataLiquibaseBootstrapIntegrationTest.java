package com.ford.service.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FordMetadataLiquibaseBootstrapIntegrationTest {

    @Container
    static final FordPostgresContainer POSTGRES = new FordPostgresContainer();

    private JdbcClient jdbc;
    private FordMetadataLiquibaseBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("drop table if exists public.databasechangelog").update();
        jdbc.sql("drop table if exists public.databasechangeloglock").update();
        jdbc.sql("drop table if exists ford_metadata.databasechangelog").update();
        jdbc.sql("drop table if exists ford_metadata.databasechangeloglock").update();
        jdbc.sql("drop schema if exists ford_metadata").update();
        jdbc.sql("drop table if exists unexpected_history.databasechangelog").update();
        jdbc.sql("drop schema if exists unexpected_history").update();
        bootstrap = new FordMetadataLiquibaseBootstrap(dataSource);
    }

    @Test
    void createsMetadataSchemaOnFreshDatabase() {
        assertThat(jdbc.sql("select exists (select 1 from pg_roles where rolname = 'ford_catalog_reader')")
                .query(Boolean.class).single()).isTrue();
        bootstrap.initialize();
        bootstrap.initialize();

        assertThat(jdbc.sql("select to_regnamespace('ford_metadata') is not null")
                .query(Boolean.class).single()).isTrue();
        assertThat(tableExists("ford_metadata", "databasechangelog")).isFalse();
    }

    @Test
    void movesExistingLiquibaseHistoryAndLockWithoutChangingRows() {
        jdbc.sql("create table public.databasechangelog (id text, author text, filename text, md5sum text)").update();
        jdbc.sql("insert into public.databasechangelog values ('001-platform', 'ford-service', 'changes/001.yaml', '8:unchanged')").update();
        jdbc.sql("create table public.databasechangeloglock (id integer primary key, locked boolean, lockgranted timestamp, lockedby text)").update();
        jdbc.sql("insert into public.databasechangeloglock values (1, true, current_timestamp, 'synthetic-test')").update();

        bootstrap.initialize();

        assertThat(tableExists("ford_metadata", "databasechangelog")).isTrue();
        assertThat(tableExists("ford_metadata", "databasechangeloglock")).isTrue();
        assertThat(tableExists("public", "databasechangelog")).isFalse();
        assertThat(jdbc.sql("select id || ':' || author || ':' || md5sum from ford_metadata.databasechangelog")
                .query(String.class).single()).isEqualTo("001-platform:ford-service:8:unchanged");
        assertThat(jdbc.sql("select lockedby from ford_metadata.databasechangeloglock where id = 1")
                .query(String.class).single()).isEqualTo("synthetic-test");
    }

    @Test
    void refusesToMergeTrackingTablesFoundInDifferentSchemas() {
        jdbc.sql("create table public.databasechangelog (id text)").update();
        jdbc.sql("create table public.databasechangeloglock (id integer)").update();
        jdbc.sql("create schema unexpected_history").update();
        jdbc.sql("create table unexpected_history.databasechangelog (id text)").update();

        assertThatThrownBy(bootstrap::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous");
    }

    private boolean tableExists(String schema, String table) {
        return jdbc.sql("select exists (select 1 from information_schema.tables where table_schema = :schema and table_name = :table)")
                .param("schema", schema).param("table", table).query(Boolean.class).single();
    }
}
