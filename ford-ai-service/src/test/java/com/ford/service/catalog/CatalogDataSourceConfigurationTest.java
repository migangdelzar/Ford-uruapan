package com.ford.service.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.catalog.adapter.out.CatalogDataSourceConfiguration;
import com.ford.service.catalog.adapter.out.CatalogDataSourceProperties;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.CatalogRetrievalPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogDataSourceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CatalogDataSourceConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void localCatalogIsDisabledWithoutCreatingAnotherDatasourceByDefault() {
        contextRunner.withPropertyValues("ford.catalog.tools.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("catalogDataSource");
                    assertThat(context).doesNotHaveBean(CatalogPort.class);
                    assertThat(context).doesNotHaveBean(CatalogRetrievalPort.class);
                });
    }

    @Test
    void enablingLocalCatalogRequiresDedicatedReadOnlyConnectionSettings() {
        contextRunner.withPropertyValues("ford.catalog.tools.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("dedicated read-only catalog JDBC URL and credentials");
                });
    }

    @Test
    void enablingLocalCatalogCreatesAnIndependentDatasourceAndPort() {
        contextRunner.withPropertyValues(
                        "ford.catalog.tools.enabled=true",
                        "ford.catalog.datasource.url=jdbc:h2:mem:catalog-config",
                        "ford.catalog.datasource.username=sa",
                        "ford.catalog.datasource.password=test-only",
                        "ford.catalog.datasource.driver-class-name=org.h2.Driver")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("catalogDataSource");
                    assertThat(context).hasSingleBean(CatalogPort.class);
                    assertThat(context).hasSingleBean(CatalogRetrievalPort.class);
                    assertThat(context.getBean("catalogDataSource", DataSource.class)).isNotNull();
                });
    }

    @Test
    void refusesToUseFordServiceDatabaseAsSyntheticCatalogStore() {
        contextRunner.withPropertyValues(
                        "ford.catalog.tools.enabled=true",
                        "ford.catalog.datasource.url=jdbc:postgresql://localhost:55432/ford_service",
                        "ford.catalog.datasource.username=synthetic_reader",
                        "ford.catalog.datasource.password=synthetic-only")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("must not use the ford_service database");
                });
    }

    @Test
    void ociVaultRequiresSecretOcid() {
        ociVaultProperties("jdbc:postgresql://localhost:5432/ford_catalog", "ford_catalog_reader", "")
                .withPropertyValues("ford.catalog.datasource.password-secret-ocid=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("valid OCI Vault secret OCID");
                });
    }

    @Test
    void ociVaultRejectsInvalidSecretOcid() {
        ociVaultProperties("jdbc:postgresql://localhost:5432/ford_catalog", "ford_catalog_reader", "")
                .withPropertyValues("ford.catalog.datasource.password-secret-ocid=ocid1.vaultsecret.")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("valid OCI Vault secret OCID");
                });
    }

    @Test
    void ociVaultAcceptsValidSecretAndDedicatedCatalogConnection() {
        ociVaultProperties("jdbc:postgresql://localhost:5432/ford_catalog", "ford_catalog_reader", "")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CatalogDataSourceProperties.class).secretsProvider()).isEqualTo("oci-vault");
                    assertThat(context.getBean(CatalogDataSourceProperties.class).passwordSecretOcid())
                            .isEqualTo("ocid1.vaultsecret.oc1..opaque_suffix-1");
                });
    }

    @Test
    void ociVaultRequiresDedicatedCatalogReaderUsername() {
        ociVaultProperties("jdbc:postgresql://localhost:5432/ford_catalog", "synthetic_reader", "")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("ford_catalog_reader");
                });
    }

    @Test
    void ociVaultRejectsFordServiceDatabase() {
        ociVaultProperties("jdbc:postgresql://localhost:5432/ford_service", "ford_catalog_reader", "")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("ford_catalog database");
                });
    }

    @Test
    void ociVaultRejectsOtherDatabase() {
        ociVaultProperties("jdbc:postgresql://localhost:5432/other", "ford_catalog_reader", "")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("ford_catalog database");
                });
    }

    @Test
    void ociVaultRejectsEnvironmentPasswordFallback() {
        ociVaultProperties("jdbc:postgresql://localhost:5432/ford_catalog", "ford_catalog_reader", "fallback")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("must not configure an environment password");
                });
    }

    private ApplicationContextRunner ociVaultProperties(String url, String username, String password) {
        return contextRunner.withPropertyValues(
                "ford.catalog.tools.enabled=true",
                "ford.catalog.datasource.secrets-provider=oci-vault",
                "ford.catalog.datasource.password-secret-ocid=ocid1.vaultsecret.oc1..opaque_suffix-1",
                "ford.catalog.datasource.url=" + url,
                "ford.catalog.datasource.username=" + username,
                "ford.catalog.datasource.password=" + password);
    }

    @Test
    void catalogPasswordUsesOnlyItsDedicatedEnvironmentVariable() throws Exception {
        assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
                .contains("secrets-provider: ${FORD_SECRETS_PROVIDER:environment}")
                .contains("password-secret-ocid: ${FORD_VAULT_SECRET_OCID_FORD_CATALOG_PASSWORD:}")
                .contains("password: ${FORD_CATALOG_PASSWORD:}")
                .doesNotContain("password: ${FORD_DATABASE_PASSWORD:}");

        contextRunner.withPropertyValues(
                        "ford.catalog.tools.enabled=true",
                        "ford.catalog.datasource.url=jdbc:h2:mem:catalog-password-fallback",
                        "ford.catalog.datasource.username=ford_catalog_reader",
                        "ford.catalog.datasource.password=${FORD_CATALOG_PASSWORD}",
                        "FORD_CATALOG_PASSWORD=dedicated-catalog-password",
                        "FORD_DATABASE_PASSWORD=existing-db-password",
                        "ford.catalog.datasource.driver-class-name=org.h2.Driver")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CatalogDataSourceProperties.class).password())
                            .isEqualTo("dedicated-catalog-password");
                });

        contextRunner.withPropertyValues(
                        "ford.catalog.tools.enabled=true",
                        "ford.catalog.datasource.url=jdbc:h2:mem:catalog-password-empty",
                        "ford.catalog.datasource.username=ford_catalog_reader",
                        "ford.catalog.datasource.password=${FORD_CATALOG_PASSWORD:}",
                        "FORD_CATALOG_PASSWORD=",
                        "FORD_DATABASE_PASSWORD=existing-db-password",
                        "ford.catalog.datasource.driver-class-name=org.h2.Driver")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("dedicated read-only catalog JDBC URL and credentials");
                });
    }
}
