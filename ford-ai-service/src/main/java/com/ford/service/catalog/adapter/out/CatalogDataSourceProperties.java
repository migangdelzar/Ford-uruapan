package com.ford.service.catalog.adapter.out;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties(prefix = "ford.catalog.datasource")
public class CatalogDataSourceProperties {

    private static final Set<String> SAFE_JDBC_OPTIONS = Set.of(
            "sslmode", "connecttimeout", "sockettimeout", "tcpkeepalive", "applicationname");

    private String url = "";
    private String username = "";
    private String password = "";
    private String secretsProvider = "environment";
    private String passwordSecretOcid = "";
    private String driverClassName = "org.postgresql.Driver";

    public String url() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String username() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String password() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String secretsProvider() { return secretsProvider; }
    public void setSecretsProvider(String secretsProvider) { this.secretsProvider = secretsProvider; }
    public String passwordSecretOcid() { return passwordSecretOcid; }
    public void setPasswordSecretOcid(String passwordSecretOcid) { this.passwordSecretOcid = passwordSecretOcid; }
    public String driverClassName() { return driverClassName; }
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }

    void validate() {
        if ("oci-vault".equals(secretsProvider)) {
            validateVaultConfiguration();
            return;
        }
        if (!"environment".equals(secretsProvider)) {
            throw new IllegalStateException("Unsupported catalog datasource secrets provider");
        }
        if (url == null || url.isBlank() || username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Local Ford catalog tools require a dedicated read-only catalog JDBC URL and credentials");
        }
        if (isFordServiceDatabase(url)) {
            throw new IllegalStateException("Synthetic catalog datasource must not use the ford_service database");
        }
    }

    private void validateVaultConfiguration() {
        if (!isValidVaultSecretOcid(passwordSecretOcid)) {
            throw new IllegalStateException("OCI Vault mode requires a valid OCI Vault secret OCID");
        }
        if (url == null || !isFordCatalogDatabase(url)) {
            throw new IllegalStateException("OCI Vault mode requires a PostgreSQL URL for the ford_catalog database");
        }
        if (hasJdbcIdentityOverrides(url)) {
            throw new IllegalStateException("OCI Vault JDBC URL must not override connection identity or credentials");
        }
        if (!"ford_catalog_reader".equals(username)) {
            throw new IllegalStateException("OCI Vault mode requires username exactly ford_catalog_reader");
        }
        if (password != null && !password.isBlank()) {
            throw new IllegalStateException("OCI Vault mode must not configure an environment password");
        }
    }

    private static boolean isValidVaultSecretOcid(String ocid) {
        return ocid != null
                && ocid.matches("ocid1\\.vaultsecret\\.[A-Za-z0-9]+\\.[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*\\.[A-Za-z0-9_-]+");
    }

    private static boolean isFordCatalogDatabase(String jdbcUrl) {
        String prefix = "jdbc:postgresql://";
        var normalized = jdbcUrl.strip();
        if (!normalized.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        var urlWithoutOptions = normalized.substring(prefix.length()).split("[?;]", 2)[0];
        var pathStart = urlWithoutOptions.indexOf('/');
        return pathStart > 0 && "/ford_catalog".equals(urlWithoutOptions.substring(pathStart));
    }

    private static boolean hasJdbcIdentityOverrides(String jdbcUrl) {
        var queryStart = jdbcUrl.indexOf('?');
        if (queryStart < 0) {
            return false;
        }
        var query = jdbcUrl.substring(queryStart + 1);
        var seen = new HashSet<String>();
        for (var parameter : query.split("[&;]", -1)) {
            if (parameter.isEmpty()) {
                return true;
            }
            var equals = parameter.indexOf('=');
            if (equals <= 0) {
                return true;
            }
            final String name;
            try {
                name = decodeJdbcOption(parameter.substring(0, equals)).toLowerCase(Locale.ROOT);
                decodeJdbcOption(parameter.substring(equals + 1));
            } catch (IllegalArgumentException malformedEncoding) {
                return true;
            }
            if (name.isBlank() || !SAFE_JDBC_OPTIONS.contains(name) || !seen.add(name)) {
                return true;
            }
        }
        return false;
    }

    private static String decodeJdbcOption(String component) {
        for (int index = 0; index < component.length(); index++) {
            if (component.charAt(index) == '%') {
                if (index + 2 >= component.length()
                        || Character.digit(component.charAt(index + 1), 16) < 0
                        || Character.digit(component.charAt(index + 2), 16) < 0) {
                    throw new IllegalArgumentException("Malformed URL encoding");
                }
                index += 2;
            }
        }
        var decoded = URLDecoder.decode(component, StandardCharsets.UTF_8);
        if (decoded.indexOf('\uFFFD') >= 0) {
            throw new IllegalArgumentException("Malformed UTF-8 encoding");
        }
        return decoded;
    }

    private static boolean isFordServiceDatabase(String jdbcUrl) {
        var normalized = jdbcUrl.strip();
        if (!normalized.regionMatches(true, 0, "jdbc:postgresql:", 0, "jdbc:postgresql:".length())) {
            return false;
        }
        var databasePart = normalized.substring("jdbc:postgresql:".length()).split("[?;]", 2)[0];
        var slash = databasePart.lastIndexOf('/');
        var databaseName = slash >= 0 ? databasePart.substring(slash + 1) : databasePart;
        return "ford_service".equalsIgnoreCase(databaseName);
    }
}
