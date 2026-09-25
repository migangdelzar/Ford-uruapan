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
            statement.execute("create extension if not exists vector");
            try (var result = statement.executeQuery("select to_regtype('public.vector')")) {
                if (!result.next() || result.getString(1) == null) {
                    throw new SQLException("pgvector extension type is unavailable after initialization");
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize pgvector test database", exception);
        }
    }
}
