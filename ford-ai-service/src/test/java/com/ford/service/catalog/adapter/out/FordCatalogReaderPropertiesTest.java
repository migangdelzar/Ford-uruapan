package com.ford.service.catalog.adapter.out;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FordCatalogReaderPropertiesTest {

    @Test
    void readerIsDisabledByDefaultAndNeedsNoCredentials() {
        var properties = new FordCatalogReaderProperties();

        assertThat(properties.toolsEnabled()).isFalse();
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void enabledReaderRequiresTestOnlyCredentialsAndSecretIdentifier() {
        var properties = new FordCatalogReaderProperties();
        properties.setToolsEnabled(true);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ford catalog database secret OCID is required when catalog is enabled");
    }

    @Test
    void enabledReaderAcceptsExplicitTestOnlySettings() {
        var properties = new FordCatalogReaderProperties();
        properties.setToolsEnabled(true);
        properties.setHost("127.0.0.1");
        properties.setUsername("ford_catalog_reader");
        properties.setPassword("integration-test-only");
        properties.setSecretOcid("test-only-not-a-real-ocid");

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.jdbcUrl())
                .isEqualTo("jdbc:postgresql://127.0.0.1:5432/ford?currentSchema=ford_catalog&connectTimeout=5&socketTimeout=30&tcpKeepAlive=true");
    }
}
