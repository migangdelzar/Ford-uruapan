package com.ford.service.config.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OciVaultEnvironmentPostProcessorTest {

    @Test
    void leavesEnvironmentModeAlone() {
        var fetcherCreated = new AtomicBoolean();
        var processor = new OciVaultEnvironmentPostProcessor(() -> {
            fetcherCreated.set(true);
            return secretId -> "unused";
        });
        var environment = new MockEnvironment()
                .withProperty("FORD_SECRETS_PROVIDER", "environment")
                .withProperty("OPENROUTER_API_KEY", "local-key");

        processor.postProcessEnvironment(environment, null);

        assertThat(fetcherCreated).isFalse();
        assertThat(environment.getProperty("OPENROUTER_API_KEY")).isEqualTo("local-key");
    }

    @Test
    void addsVaultValuesAsHighPriorityEnvironmentProperties() {
        var processor = new OciVaultEnvironmentPostProcessor(
                () -> secretId -> "replacement-provider-key");
        var environment = new MockEnvironment()
                .withProperty("FORD_SECRETS_PROVIDER", "oci-vault")
                .withProperty("FORD_VAULT_SECRET_OCID_OPENROUTER_API_KEY", "ocid1.vaultsecret.example")
                .withProperty("OPENROUTER_API_KEY", "stale-environment-key");

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("OPENROUTER_API_KEY"))
                .isEqualTo("replacement-provider-key");
    }

    @Test
    void failsClosedWhenVaultModeHasNoSecretMappings() {
        var processor = new OciVaultEnvironmentPostProcessor(() -> secretId -> "unused");
        var environment = new MockEnvironment()
                .withProperty("FORD_SECRETS_PROVIDER", "oci-vault");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FORD_VAULT_SECRET_OCID_<ENV_NAME>");
    }

    @Test
    void closesVaultFetcherAfterResolvingMappedSecrets() {
        var fetcher = new RecordingSecretFetcher();
        var processor = new OciVaultEnvironmentPostProcessor(() -> fetcher);
        var environment = new MockEnvironment()
                .withProperty("FORD_SECRETS_PROVIDER", "oci-vault")
                .withProperty("FORD_VAULT_SECRET_OCID_OPENROUTER_API_KEY", "test-secret-id");

        processor.postProcessEnvironment(environment, null);

        assertThat(fetcher.secretIds).containsExactly("test-secret-id");
        assertThat(fetcher.closed).isTrue();
    }

    @Test
    void closesVaultFetcherWhenVaultModeHasNoMappings() {
        var fetcher = new RecordingSecretFetcher();
        var processor = new OciVaultEnvironmentPostProcessor(() -> fetcher);
        var environment = new MockEnvironment().withProperty("FORD_SECRETS_PROVIDER", "oci-vault");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(fetcher.closed).isTrue();
    }

    private static final class RecordingSecretFetcher implements VaultSecretValueFetcher {

        private final List<String> secretIds = new ArrayList<>();
        private boolean closed;

        @Override
        public String fetchSecretValue(String secretId) {
            secretIds.add(secretId);
            return "test-secret-value";
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
