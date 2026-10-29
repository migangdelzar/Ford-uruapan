package com.ford.service.conversations;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.nio.file.InvalidPathException;
import java.net.URI;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;

/** One-shot, local-only importer for the approved filtered catalog export. */
final class LocalCatalogSnapshotImportCli {
    private static final String DEFAULT_EXPORT_DIRECTORY = "build/reports/local-qwen/source-export";

    private LocalCatalogSnapshotImportCli() { }

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("catalog importer does not accept arguments");
        var environment = System.getenv();
        var jdbcUrl = valueOrDefault(environment, "FORD_CATALOG_ADMIN_JDBC_URL",
                "jdbc:postgresql://localhost:" + port(environment) + "/ford_catalog");
        Path exportDirectory;
        try {
            exportDirectory = Path.of(valueOrDefault(environment, "FORD_LOCAL_CATALOG_EXPORT_DIR",
                    DEFAULT_EXPORT_DIRECTORY));
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException("catalog export directory setting is invalid");
        }
        var adminUsername = required(environment, "FORD_CATALOG_ADMIN_USERNAME");
        var adminPassword = required(environment, "FORD_CATALOG_ADMIN_PASSWORD");
        var readerRole = required(environment, "FORD_CATALOG_USERNAME");
        var readerPassword = required(environment, "FORD_CATALOG_PASSWORD");

        validateTarget(jdbcUrl);
        var dataSource = new DriverManagerDataSource(jdbcUrl, adminUsername, adminPassword);
        var summary = importSnapshot(exportDirectory, dataSource, jdbcUrl, readerRole, readerPassword);
        System.out.printf("source=%s refreshedAt=%s rowCounts=%s%n",
                LocalCatalogSnapshotLoader.SOURCE_LABEL, summary.refreshedAt(), summary.rowCounts());
    }

    static LocalCatalogSnapshotLoader.SnapshotSummary importSnapshot(
            Path exportDirectory, DataSource adminDataSource, String jdbcUrl,
            String readerRole, String readerPassword) {
        Objects.requireNonNull(exportDirectory, "catalog export directory is required");
        Objects.requireNonNull(adminDataSource, "catalog admin data source is required");
        validateTarget(jdbcUrl);
        validateReaderCredentials(readerRole, readerPassword);

        // Validate the complete snapshot before any database change, and reject test fixtures here.
        try {
            LocalCatalogSnapshotLoader.validateApprovedSnapshot(exportDirectory);
        } catch (IllegalArgumentException failure) {
            // Avoid printing filesystem paths from an underlying I/O exception.
            throw new IllegalArgumentException(failure.getMessage());
        }
        validateDataSourceDatabase(adminDataSource);
        createReaderRole(adminDataSource, readerRole, readerPassword);
        try {
            return LocalCatalogSnapshotLoader.load(exportDirectory, adminDataSource, readerRole);
        } catch (RuntimeException | Error failure) {
            try {
                dropReaderRole(adminDataSource, readerRole);
            } catch (RuntimeException cleanupFailure) {
                throw new IllegalStateException(
                        "local catalog import failed; reader-role cleanup failed; inspect the dedicated local catalog");
            }
            throw new IllegalStateException("local catalog import failed; schema/data transaction rolled back "
                    + "and the new reader role was removed");
        }
    }

    private static void validateDataSourceDatabase(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            if (!"ford_catalog".equalsIgnoreCase(connection.getCatalog())) {
                throw invalidTarget();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not verify local catalog database target");
        }
    }

    static void validateTarget(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank() || !jdbcUrl.startsWith("jdbc:")) {
            throw invalidTarget();
        }
        URI uri;
        try {
            uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException failure) {
            throw invalidTarget();
        }
        var host = uri.getHost();
        var port = uri.getPort();
        if (!"postgresql".equalsIgnoreCase(uri.getScheme())
                || host == null || !isLoopbackHost(host)
                || uri.getUserInfo() != null || !"/ford_catalog".equals(uri.getPath())
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || port == 0 || port > 65535) {
            throw invalidTarget();
        }
    }

    private static boolean isLoopbackHost(String host) {
        var normalized = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        if ("localhost".equalsIgnoreCase(normalized) || "::1".equals(normalized)) return true;
        if (!normalized.matches("127(?:\\.[0-9]{1,3}){3}")) return false;
        try {
            return InetAddress.getByName(normalized).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void validateReaderCredentials(String readerRole, String readerPassword) {
        if (readerRole == null || !readerRole.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("catalog reader role name is invalid");
        }
        if (readerPassword == null || readerPassword.isBlank()) {
            throw new IllegalArgumentException("catalog reader password is required");
        }
    }

    private static void createReaderRole(DataSource dataSource, String role, String password) {
        try (var connection = dataSource.getConnection();
             var exists = connection.prepareStatement("select exists(select 1 from pg_roles where rolname = ?)") ) {
            exists.setString(1, role);
            try (var result = exists.executeQuery()) {
                if (result.next() && result.getBoolean(1)) {
                    throw new IllegalArgumentException("catalog reader role already exists; refusing to reuse it");
                }
            }
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE \"" + role + "\" LOGIN PASSWORD '" + quoteLiteral(password) + "'");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not create local catalog reader role");
        }
    }

    private static void dropReaderRole(DataSource dataSource, String role) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP ROLE \"" + role + "\"");
        } catch (SQLException failure) {
            throw new IllegalStateException("could not clean up local catalog reader role");
        }
    }

    private static String quoteLiteral(String value) {
        return value.replace("'", "''");
    }

    private static String required(Map<String, String> environment, String name) {
        var value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String valueOrDefault(Map<String, String> environment, String name, String fallback) {
        var value = environment.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String port(Map<String, String> environment) {
        var value = valueOrDefault(environment, "FORD_CATALOG_PORT", "55433");
        try {
            var port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return Integer.toString(port);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("FORD_CATALOG_PORT must be a valid TCP port");
        }
    }

    private static IllegalArgumentException invalidTarget() {
        return new IllegalArgumentException("catalog importer requires a loopback PostgreSQL URL for ford_catalog");
    }
}
