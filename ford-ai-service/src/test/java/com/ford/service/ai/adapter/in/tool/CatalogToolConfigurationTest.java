package com.ford.service.ai.adapter.in.tool;

import com.ford.service.catalog.adapter.out.JdbcFordCatalogKnowledgeReader;
import com.ford.service.catalog.adapter.out.JdbcFordCatalogReader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CatalogToolConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CatalogToolConfiguration.class);

    @Test
    void catalogToolsAreAbsentByDefault() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(FordCatalogTools.class));
    }

    @Test
    void catalogToolsAppearOnlyWhenEnabledWithBothReadAdapters() {
        contextRunner
                .withUserConfiguration(ReaderConfiguration.class)
                .withPropertyValues("ford.catalog.reader.tools-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(FordCatalogTools.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class ReaderConfiguration {
        @Bean
        JdbcFordCatalogReader catalogReader() {
            return mock(JdbcFordCatalogReader.class);
        }

        @Bean
        JdbcFordCatalogKnowledgeReader knowledgeReader() {
            return mock(JdbcFordCatalogKnowledgeReader.class);
        }
    }
}
