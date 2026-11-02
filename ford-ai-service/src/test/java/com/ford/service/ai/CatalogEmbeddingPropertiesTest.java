package com.ford.service.ai;

import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.ai.configuration.CatalogEmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogEmbeddingPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CatalogEmbeddingPropertiesConfiguration.class);

    @Test
    void defaultsCatalogEmbeddingsToStoredBgeM3WithoutChangingGlobalEmbeddingModel() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CatalogEmbeddingProperties.class).model()).isEqualTo("bge-m3");
            assertThat(new AiProperties().embeddingModelVersion()).isEqualTo("configured-embedding-model");
        });
    }

    @Test
    void bindsCatalogModelSeparatelyFromGlobalAiProperties() {
        contextRunner.withPropertyValues("ford.catalog.embedding.model=wrong-tag")
                .run(context -> {
                    assertThat(context.getBean(CatalogEmbeddingProperties.class).model()).isEqualTo("wrong-tag");
                    assertThat(new AiProperties().embeddingModelVersion()).isEqualTo("configured-embedding-model");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CatalogEmbeddingProperties.class)
    static class CatalogEmbeddingPropertiesConfiguration { }
}
