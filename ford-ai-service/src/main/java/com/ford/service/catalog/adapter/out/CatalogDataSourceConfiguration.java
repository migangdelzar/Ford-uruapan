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

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogDataSourceProperties.class)
public class CatalogDataSourceConfiguration {

    @Bean(name = "catalogDataSource", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "ford.catalog.datasource", name = "tools-enabled", havingValue = "true")
    DataSource catalogDataSource(CatalogDataSourceProperties properties) {
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

    @Bean(name = "catalogJdbcClient")
    @ConditionalOnBean(name = "catalogDataSource")
    JdbcClient catalogJdbcClient(@org.springframework.beans.factory.annotation.Qualifier("catalogDataSource")
                                 DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean(name = "catalogQueryEmbeddingModel")
    @ConditionalOnProperty(prefix = "ford.catalog.datasource", name = "tools-enabled", havingValue = "true")
    CatalogQueryEmbeddingModel catalogQueryEmbeddingModel(OllamaApi ollamaApi,
                                                           CatalogDataSourceProperties properties) {
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
