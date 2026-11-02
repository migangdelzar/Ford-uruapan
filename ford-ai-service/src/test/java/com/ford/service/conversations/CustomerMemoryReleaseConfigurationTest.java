package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerMemoryReleaseConfigurationTest {

    @Test
    void productionProfileKeepsMemoryDisabledAndSecretsExternallySupplied() throws Exception {
        var production = Files.readString(Path.of("src/main/resources/application-prod.yml"));

        assertThat(production)
                .contains("enabled: ${FORD_CUSTOMER_MEMORY_ENABLED:false}")
                .contains("hmac-key: ${FORD_CUSTOMER_MEMORY_HMAC_KEY:}")
                .contains("hmac-key-version: ${FORD_CUSTOMER_MEMORY_HMAC_KEY_VERSION:}")
                .contains("previous-hmac-key: ${FORD_CUSTOMER_MEMORY_PREVIOUS_HMAC_KEY:}")
                .contains("previous-hmac-key-version: ${FORD_CUSTOMER_MEMORY_PREVIOUS_HMAC_KEY_VERSION:}");
    }

    @Test
    void releaseChangelogIncludesAdditiveCustomerMemoryMigration() throws Exception {
        var changelog = Files.readString(Path.of("src/main/resources/db/changelog/db.changelog-master.yaml"));

        assertThat(changelog).contains("db/changelog/changes/028-customer-memory-profile.yaml");
    }
}
