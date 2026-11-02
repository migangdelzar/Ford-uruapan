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
        jdbc.sql("drop table if exists ford.databasechangelog").update();
        jdbc.sql("drop table if exists ford.databasechangeloglock").update();
        jdbc.sql("drop schema if exists ford").update();
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
    void refusesToMergeHistoriesSplitBetweenFordAndPublicWithoutMovingRows() {
        jdbc.sql("create schema ford").update();
        jdbc.sql("create table public.databasechangelog (id text, author text, filename text, md5sum text)").update();
        jdbc.sql("create table public.databasechangeloglock (id integer primary key, locked boolean, lockgranted timestamp, lockedby text)").update();
        jdbc.sql("create table ford.databasechangelog (id text, author text, filename text, md5sum text)").update();
        jdbc.sql("create table ford.databasechangeloglock (id integer primary key, locked boolean, lockgranted timestamp, lockedby text)").update();
        jdbc.sql("insert into public.databasechangelog values ('public-001', 'test', 'public.yaml', '8:public')").update();
        jdbc.sql("insert into public.databasechangeloglock values (1, false, null, 'public-lock')").update();
        jdbc.sql("insert into ford.databasechangelog values ('ford-001', 'test', 'ford.yaml', '8:ford')").update();
        jdbc.sql("insert into ford.databasechangeloglock values (1, false, null, 'ford-lock')").update();

        assertThatThrownBy(bootstrap::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous");

        assertThat(tableExists("public", "databasechangelog")).isTrue();
        assertThat(tableExists("public", "databasechangeloglock")).isTrue();
        assertThat(tableExists("ford", "databasechangelog")).isTrue();
        assertThat(tableExists("ford", "databasechangeloglock")).isTrue();
        assertThat(jdbc.sql("select md5sum from public.databasechangelog where id = 'public-001'")
                .query(String.class).single()).isEqualTo("8:public");
        assertThat(jdbc.sql("select md5sum from ford.databasechangelog where id = 'ford-001'")
                .query(String.class).single()).isEqualTo("8:ford");
        assertThat(jdbc.sql("select lockedby from public.databasechangeloglock where id = 1")
                .query(String.class).single()).isEqualTo("public-lock");
        assertThat(jdbc.sql("select lockedby from ford.databasechangeloglock where id = 1")
                .query(String.class).single()).isEqualTo("ford-lock");
    }

    private boolean tableExists(String schema, String table) {
        return jdbc.sql("select exists (select 1 from information_schema.tables where table_schema = :schema and table_name = :table)")
                .param("schema", schema).param("table", table).query(Boolean.class).single();
    }
}
