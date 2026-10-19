package com.ford.service.catalog.adapter.out;

import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.CatalogQueryEmbedder;
import com.ford.service.catalog.api.CatalogRetrievalPort;
import com.ford.service.catalog.application.CatalogRetrievalService;
import com.ford.service.knowledge.application.ReciprocalRankFusion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogDataSourceProperties.class)
@ConditionalOnProperty(prefix = "ford.catalog.tools", name = "enabled", havingValue = "true")
public class CatalogDataSourceConfiguration {

    @Bean(name = "catalogDataSource", destroyMethod = "close")
    DataSource catalogDataSource(CatalogDataSourceProperties properties) {
        properties.validate();
        return DataSourceBuilder.create()
                .driverClassName(properties.driverClassName())
                .url(properties.url())
                .username(properties.username())
                .password(properties.password())
                .build();
    }

    @Bean
    CatalogPort catalogPort(@Qualifier("catalogDataSource") DataSource catalogDataSource,
                                   ObjectMapper objectMapper) {
        return new JdbcCatalogAdapter(JdbcClient.create(catalogDataSource), objectMapper);
    }

    @Bean
    CatalogRetrievalPort catalogRetrievalPort(CatalogPort catalogPort,
                                               ObjectProvider<CatalogQueryEmbedder> queryEmbedders) {
        return new CatalogRetrievalService(catalogPort, queryEmbedders.getIfAvailable(),
                new ReciprocalRankFusion());
    }
}
