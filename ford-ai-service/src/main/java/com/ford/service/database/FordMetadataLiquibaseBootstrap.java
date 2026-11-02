package com.ford.service.database;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Creates the Liquibase tracking schema and safely relocates existing history before Liquibase starts. */
public final class FordMetadataLiquibaseBootstrap {

    private static final String TARGET_SCHEMA = "ford_metadata";
    private static final String LEGACY_SCHEMA = "public";
    private static final List<String> TRACKING_TABLES = List.of("databasechangelog", "databasechangeloglock");

    private final DataSource dataSource;

    public FordMetadataLiquibaseBootstrap(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public void initialize() {
        try (var connection = dataSource.getConnection()) {
            var originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                createTargetSchema(connection);
                if (isPostgreSql(connection)) {
                    relocateHistoryIfPresent(connection);
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Could not bootstrap Ford Liquibase metadata", exception);
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not bootstrap Ford Liquibase metadata", exception);
        }
    }

    private static void createTargetSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("create schema if not exists ford_metadata");
        }
    }

    private static boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL");
    }

    private static void relocateHistoryIfPresent(Connection connection) throws SQLException {
        var history = findRelations(connection, TRACKING_TABLES.getFirst());
        var lock = findRelations(connection, TRACKING_TABLES.get(1));
        if (history.isEmpty() && lock.isEmpty()) {
            return;
        }
        if (history.size() != 1 || lock.size() != 1) {
            throw new IllegalStateException("Liquibase history location is ambiguous or incomplete");
        }
        var historyRelation = history.getFirst();
        var lockRelation = lock.getFirst();
        if (!historyRelation.schema().equals(lockRelation.schema())) {
            throw new IllegalStateException("Liquibase history and lock tables are in different schemas");
        }
        if (!"r".equals(historyRelation.kind()) || !"r".equals(lockRelation.kind())) {
            throw new IllegalStateException("Liquibase tracking relations must be ordinary tables");
        }
        var sourceSchema = historyRelation.schema();
        if (TARGET_SCHEMA.equals(sourceSchema)) {
            return;
        }
        if (!LEGACY_SCHEMA.equals(sourceSchema)) {
            throw new IllegalStateException("Unverified Liquibase history schema: " + sourceSchema);
        }
        if (!findRelations(connection, TRACKING_TABLES.getFirst(), TARGET_SCHEMA).isEmpty()
                || !findRelations(connection, TRACKING_TABLES.get(1), TARGET_SCHEMA).isEmpty()) {
            throw new IllegalStateException("Liquibase history already exists in ford_metadata");
        }
        moveTable(connection, historyRelation);
        moveTable(connection, lockRelation);
    }

    private static List<Relation> findRelations(Connection connection, String relationName) throws SQLException {
        return findRelations(connection, relationName, null);
    }

    private static List<Relation> findRelations(Connection connection, String relationName, String schema)
            throws SQLException {
        var sql = """
                select n.nspname, c.relname, c.relkind
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where lower(c.relname) = lower(?)
                  and n.nspname not in ('pg_catalog', 'information_schema')
                  and n.nspname not like 'pg\\_%' escape '\\'
                """ + (schema == null ? "" : " and n.nspname = ?")
                + " order by n.nspname";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, relationName);
            if (schema != null) {
                statement.setString(2, schema);
            }
            try (var result = statement.executeQuery()) {
                var relations = new ArrayList<Relation>();
                while (result.next()) {
                    relations.add(new Relation(result.getString(1), result.getString(2), result.getString(3)));
                }
                return relations;
            }
        }
    }

    private static void moveTable(Connection connection, Relation relation) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("alter table " + quote(relation.schema()) + "." + quote(relation.name())
                    + " set schema " + quote(TARGET_SCHEMA));
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record Relation(String schema, String name, String kind) { }
}
