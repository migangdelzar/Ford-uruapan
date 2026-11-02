package com.ford.service.database;

import org.testcontainers.containers.PostgreSQLContainer;

/** PostgreSQL fixture that leaves application bootstrap entirely to Liquibase. */
public final class FordBarePostgresContainer extends PostgreSQLContainer<FordBarePostgresContainer> {

    public FordBarePostgresContainer() {
        super("pgvector/pgvector:pg16");
    }
}
