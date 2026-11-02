package com.ford.service.database;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;

public final class FordPostgresContainer extends PostgreSQLContainer<FordPostgresContainer> {

    public FordPostgresContainer() {
        super("pgvector/pgvector:pg16");
    }

    @Override
    public void start() {
        super.start();
        try (var connection = DriverManager.getConnection(getJdbcUrl(), getUsername(), getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create schema if not exists ford_metadata");
            statement.execute("create schema if not exists ford_catalog");
            statement.execute("create extension if not exists vector with schema ford_catalog");
            statement.execute("create extension if not exists pg_trgm with schema ford_catalog");
            var testRole = "\"" + getUsername().replace("\"", "\"\"") + "\"";
            statement.execute("alter role " + testRole + " set search_path to ford_metadata, ford_catalog");
            statement.execute("do $$ begin if not exists (select 1 from pg_roles where rolname = 'ford_catalog_reader') then create role ford_catalog_reader login password 'integration-test-only'; end if; end $$");
            try (var result = statement.executeQuery("select to_regtype('ford_catalog.vector')")) {
                if (!result.next() || result.getString(1) == null) {
                    throw new SQLException("pgvector extension type is unavailable after initialization");
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize pgvector test database", exception);
        }
    }
}
