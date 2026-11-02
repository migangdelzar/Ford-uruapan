package com.ford.service.catalog.adapter.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.ford.service.catalog.domain.CatalogQueryEmbeddingModel;
import com.zaxxer.hikari.HikariDataSource;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.CatalogQueryEmbedder;
import com.ford.service.catalog.api.CatalogRetrievalPort;
import com.ford.service.catalog.application.CatalogRetrievalService;
import com.ford.service.knowledge.application.ReciprocalRankFusion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CatalogDataSourceProperties.class, FordCatalogReaderProperties.class})
public class CatalogDataSourceConfiguration {

    @Bean(name = "catalogDataSource", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "ford.catalog.tools", name = "enabled", havingValue = "true")
    DataSource legacyCatalogDataSource(CatalogDataSourceProperties properties) {
        properties.validate();
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(properties.driverClassName())
                .url(properties.url())
                .username(properties.username())
                .password(properties.password())
                .build();
    }

    @Bean(name = "catalogJdbcClient")
    @ConditionalOnBean(name = "catalogDataSource")
    JdbcClient legacyCatalogJdbcClient(@Qualifier("catalogDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    @ConditionalOnBean(name = "catalogJdbcClient")
    CatalogPort legacyCatalogPort(@Qualifier("catalogJdbcClient") JdbcClient jdbc, ObjectMapper objectMapper) {
        return new JdbcCatalogAdapter(jdbc, objectMapper);
    }

    @Bean
    @ConditionalOnBean(CatalogPort.class)
    CatalogRetrievalPort legacyCatalogRetrievalPort(
            CatalogPort catalog, ObjectProvider<CatalogQueryEmbedder> queryEmbedders) {
        return new CatalogRetrievalService(catalog, queryEmbedders.getIfAvailable(), new ReciprocalRankFusion());
    }

    @Bean(name = "fordCatalogReaderDataSource", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "ford.catalog.reader", name = "tools-enabled", havingValue = "true")
    DataSource fordCatalogReaderDataSource(FordCatalogReaderProperties properties) {
        properties.validate();
        var dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(properties.jdbcUrl())
                .username(properties.username())
                .password(properties.password())
                .build();
        dataSource.setPoolName("ford-catalog-reader");
        dataSource.setReadOnly(true);
        return dataSource;
    }

    @Bean(name = "fordCatalogJdbcClient")
    @ConditionalOnBean(name = "fordCatalogReaderDataSource")
    JdbcClient fordCatalogJdbcClient(@Qualifier("fordCatalogReaderDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean(name = "catalogQueryEmbeddingModel")
    @ConditionalOnProperty(prefix = "ford.catalog.reader", name = "tools-enabled", havingValue = "true")
    CatalogQueryEmbeddingModel catalogQueryEmbeddingModel(OllamaApi ollamaApi,
                                                           FordCatalogReaderProperties properties) {
        var embeddingModel = OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .options(OllamaEmbeddingOptions.builder()
                        .model(properties.embeddingModel())
                        .truncate(false)
                        .build())
                .build();
        return new OllamaCatalogQueryEmbeddingModel(embeddingModel);
    }
}
