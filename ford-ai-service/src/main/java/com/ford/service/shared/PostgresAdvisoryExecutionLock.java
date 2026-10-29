package com.ford.service.shared;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;

/** Provides session-scoped PostgreSQL locks for work that must have one active app instance. */
@Component
public class PostgresAdvisoryExecutionLock {

    private final DataSource dataSource;

    public PostgresAdvisoryExecutionLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean runIfAcquired(long lockKey, Runnable work) {
        try (var connection = dataSource.getConnection()) {
            boolean acquired;
            try (var statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                statement.setLong(1, lockKey);
                try (var result = statement.executeQuery()) {
                    result.next();
                    acquired = result.getBoolean(1);
                }
            }
            if (!acquired) return false;

            try {
                work.run();
            } finally {
                try (var statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                    statement.setLong(1, lockKey);
                    statement.execute();
                }
            }
            return true;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not coordinate scheduled application work", exception);
        }
    }
}
