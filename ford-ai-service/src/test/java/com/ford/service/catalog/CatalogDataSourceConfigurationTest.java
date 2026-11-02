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
    void catalogPasswordUsesOnlyItsDedicatedEnvironmentVariable() throws Exception {
        assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
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
