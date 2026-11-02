package com.ford.service.config.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OciVaultSecretPropertyLoaderTest {

    @Test
    void loadsConfiguredSecretValuesUnderTheirEnvironmentPropertyNames() {
        var requestedSecretId = new AtomicReference<String>();
        var loader = new OciVaultSecretPropertyLoader(secretId -> {
            requestedSecretId.set(secretId);
            return "replacement-provider-key";
        });

        var properties = loader.load(Map.of(
                "FORD_VAULT_SECRET_OCID_OPENROUTER_API_KEY", "ocid1.vaultsecret.example"));

        assertThat(requestedSecretId).hasValue("ocid1.vaultsecret.example");
        assertThat(properties).containsOnly(Map.entry("OPENROUTER_API_KEY", "replacement-provider-key"));
    }

    @Test
    void mapsSpringRedisEnvironmentNameToCanonicalPropertyName() {
        var loader = new OciVaultSecretPropertyLoader(secretId -> "redis-password");

        var properties = loader.load(Map.of(
                "FORD_VAULT_SECRET_OCID_SPRING_DATA_REDIS_PASSWORD", "ocid1.vaultsecret.example"));

        assertThat(properties).containsOnly(Map.entry("spring.data.redis.password", "redis-password"));
    }

    @Test
    void preservesExplicitApplicationEnvironmentVariableNames() {
        var loader = new OciVaultSecretPropertyLoader(secretId -> "provider-key");

        var properties = loader.load(Map.of(
                "FORD_VAULT_SECRET_OCID_OPENROUTER_API_KEY", "ocid1.vaultsecret.example"));

        assertThat(properties).containsOnly(Map.entry("OPENROUTER_API_KEY", "provider-key"));
    }

    @Test
    void ignoresUnrelatedEnvironmentProperties() {
        var loader = new OciVaultSecretPropertyLoader(secretId -> "unused");

        var properties = loader.load(Map.of("FORD_AI_CLOUD_PROVIDER", "openai"));

        assertThat(properties).isEmpty();
    }

    @Test
    void rejectsAnEmptySecretIdWithoutIncludingASecretValueInTheError() {
        var loader = new OciVaultSecretPropertyLoader(secretId -> "unused");

        assertThatThrownBy(() -> loader.load(Map.of(
                "FORD_VAULT_SECRET_OCID_OPENAI_API_KEY", "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FORD_VAULT_SECRET_OCID_OPENAI_API_KEY");
    }

    @Test
    void rejectsASecretWithoutAUsableValue() {
        var loader = new OciVaultSecretPropertyLoader(secretId -> "  ");

        assertThatThrownBy(() -> loader.load(Map.of(
                "FORD_VAULT_SECRET_OCID_OPENAI_API_KEY", "ocid1.vaultsecret.example")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }
}
