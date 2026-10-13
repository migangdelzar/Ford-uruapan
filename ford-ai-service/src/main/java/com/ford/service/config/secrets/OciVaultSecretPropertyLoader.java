package com.ford.service.config.secrets;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class OciVaultSecretPropertyLoader {

    public static final String SECRET_OCID_PREFIX = "FORD_VAULT_SECRET_OCID_";

    private static final Pattern PROPERTY_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    private final VaultSecretValueFetcher secretFetcher;

    public OciVaultSecretPropertyLoader(VaultSecretValueFetcher secretFetcher) {
        this.secretFetcher = secretFetcher;
    }

    public Map<String, Object> load(Map<String, ?> environmentProperties) {
        var loadedProperties = new LinkedHashMap<String, Object>();
        environmentProperties.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(SECRET_OCID_PREFIX))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> loadSecret(entry.getKey(), entry.getValue(), loadedProperties));
        return Map.copyOf(loadedProperties);
    }

    private void loadSecret(String mappingName, Object configuredSecretId, Map<String, Object> loadedProperties) {
        var propertyName = mappingName.substring(SECRET_OCID_PREFIX.length());
        if (!PROPERTY_NAME.matcher(propertyName).matches()) {
            throw new IllegalStateException("Invalid OCI Vault environment property mapping: " + mappingName);
        }
        if (!(configuredSecretId instanceof String secretId) || secretId.isBlank()) {
            throw new IllegalStateException("OCI Vault secret OCID is required for " + mappingName);
        }

        var secretValue = secretFetcher.fetchSecretValue(secretId);
        if (secretValue == null || secretValue.isBlank()) {
            throw new IllegalStateException("OCI Vault returned an empty value for " + propertyName);
        }
        loadedProperties.put(propertyName, secretValue);
    }
}
