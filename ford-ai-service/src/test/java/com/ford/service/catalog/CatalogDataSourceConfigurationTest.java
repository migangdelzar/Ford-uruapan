package com.ford.service.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.catalog.adapter.out.CatalogDataSourceConfiguration;
import com.ford.service.catalog.api.CatalogPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

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
                    assertThat(context.getBean("catalogDataSource", DataSource.class)).isNotNull();
                });
    }
}
