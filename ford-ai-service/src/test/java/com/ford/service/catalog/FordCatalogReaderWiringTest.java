package com.ford.service.catalog;

import com.ford.service.catalog.adapter.out.CatalogDataSourceConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FordCatalogReaderWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CatalogDataSourceConfiguration.class)
            .withBean(OllamaApi.class, () -> mock(OllamaApi.class));

    @Test
    void dedicatedReaderDatasourceIsAbsentByDefault() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean("fordCatalogReaderDataSource"));
    }

    @Test
    void enabledReaderFailsClosedWithoutSecretIdentifierAndPassword() {
        contextRunner.withPropertyValues("ford.catalog.reader.tools-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Ford catalog database secret OCID is required");
                });
    }

    @Test
    void enabledReaderUsesDedicatedReadOnlyDatasource() {
        contextRunner.withPropertyValues(
                        "ford.catalog.reader.tools-enabled=true",
                        "ford.catalog.reader.host=127.0.0.1",
                        "ford.catalog.reader.username=ford_catalog_reader",
                        "ford.catalog.reader.password=integration-test-only",
                        "ford.catalog.reader.secret-ocid=test-only-not-a-real-ocid",
                        "ford.catalog.reader.embedding-model=bge-m3:latest")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("fordCatalogReaderDataSource");
                    var dataSource = context.getBean(
                            "fordCatalogReaderDataSource", HikariDataSource.class);
                    assertThat(dataSource.isReadOnly()).isTrue();
                    assertThat(dataSource.getJdbcUrl()).isEqualTo(
                            "jdbc:postgresql://127.0.0.1:5432/ford?currentSchema=ford_catalog&connectTimeout=5&socketTimeout=30&tcpKeepAlive=true");
                    assertThat(dataSource.getUsername()).isEqualTo("ford_catalog_reader");
                });
    }
}
