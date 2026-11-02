package com.ford.service.ai.adapter.in.tool;

import com.ford.service.catalog.adapter.out.JdbcFordCatalogKnowledgeReader;
import com.ford.service.catalog.adapter.out.JdbcFordCatalogReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CatalogToolConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "ford.catalog.reader", name = "tools-enabled", havingValue = "true")
    FordCatalogTools fordCatalogTools(JdbcFordCatalogReader catalogReader,
                                      JdbcFordCatalogKnowledgeReader knowledgeReader) {
        return new FordCatalogTools(catalogReader, knowledgeReader);
    }
}
