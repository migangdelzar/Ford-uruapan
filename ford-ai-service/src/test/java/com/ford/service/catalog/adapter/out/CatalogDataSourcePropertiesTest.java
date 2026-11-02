package com.ford.service.catalog.adapter.out;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogDataSourcePropertiesTest {

    @Test
    void isDisabledByDefaultAndNeedsNoCatalogSecret() {
        var properties = new FordCatalogReaderProperties();

        assertThat(properties.toolsEnabled()).isFalse();
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void buildsOnlyTheFordCatalogReaderUrl() {
        var properties = new FordCatalogReaderProperties();
        properties.setHost("db.internal");

        assertThat(properties.jdbcUrl()).isEqualTo(
                "jdbc:postgresql://db.internal:5432/ford?currentSchema=ford_catalog&connectTimeout=5&socketTimeout=30&tcpKeepAlive=true");
        assertThat(properties.username()).isEqualTo("ford_catalog_reader");
    }

    @Test
    void refusesToEnableCatalogWithoutVaultSecretOcid() {
        var properties = new FordCatalogReaderProperties();
        properties.setToolsEnabled(true);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret OCID");
    }

    @Test
    void refusesWriterOrAdminUsername() {
        var properties = new FordCatalogReaderProperties();
        properties.setToolsEnabled(true);
        properties.setSecretOcid("ocid1.vaultsecret.example");
        properties.setUsername("ford_catalog_admin");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ford_catalog_reader");
    }

    @Test
    void refusesToStartEnabledDatasourceWithoutResolvedPassword() {
        var properties = new FordCatalogReaderProperties();
        properties.setToolsEnabled(true);
        properties.setSecretOcid("ocid1.vaultsecret.example");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }
}
