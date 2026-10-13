package com.ford.service.config.secrets;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

public final class OciVaultEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROVIDER_PROPERTY = "FORD_SECRETS_PROVIDER";
    private static final String OCI_VAULT_PROVIDER = "oci-vault";
    private static final String PROPERTY_SOURCE_NAME = "ociVaultSecrets";

    private final Supplier<VaultSecretValueFetcher> secretFetcherFactory;

    public OciVaultEnvironmentPostProcessor() {
        this(OciVaultSecretValueFetcher::new);
    }

    OciVaultEnvironmentPostProcessor(Supplier<VaultSecretValueFetcher> secretFetcherFactory) {
        this.secretFetcherFactory = secretFetcherFactory;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!OCI_VAULT_PROVIDER.equalsIgnoreCase(environment.getProperty(PROVIDER_PROPERTY, "environment"))) {
            return;
        }

        try (var secretFetcher = secretFetcherFactory.get()) {
            var values = new OciVaultSecretPropertyLoader(secretFetcher)
                    .load(secretMappings(environment));
            if (values.isEmpty()) {
                throw new IllegalStateException("OCI Vault mode requires "
                        + OciVaultSecretPropertyLoader.SECRET_OCID_PREFIX + "<ENV_NAME> mappings");
            }
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, values));
        }
    }

    private Map<String, Object> secretMappings(ConfigurableEnvironment environment) {
        var mappings = new LinkedHashMap<String, Object>();
        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (propertySource instanceof EnumerablePropertySource<?> enumerable) {
                for (String propertyName : enumerable.getPropertyNames()) {
                    if (propertyName.startsWith(OciVaultSecretPropertyLoader.SECRET_OCID_PREFIX)) {
                        mappings.put(propertyName, environment.getProperty(propertyName));
                    }
                }
            }
        }
        return mappings;
    }
}
