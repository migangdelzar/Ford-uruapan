package com.ford.service.conversations;

import com.ford.service.ai.adapter.out.springai.OllamaCatalogQueryEmbedder;
import com.ford.service.catalog.api.CatalogQueryEmbedder;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.time.Duration;

/** Sends catalog query embeddings to the BGE-only container, leaving host Ollama for Qwen chat. */
@TestConfiguration(proxyBeanMethods = false)
class LocalCatalogEmbeddingTestConfiguration {
    @Bean
    @Primary
    CatalogQueryEmbedder localCatalogQueryEmbedder(
            @Value("${ford.test.catalog-embedding.base-url}") String baseUrl) {
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        requestFactory.setReadTimeout(Duration.ofMinutes(2));
        var api = OllamaApi.builder().baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory)).build();
        return new OllamaCatalogQueryEmbedder(api, "bge-m3");
    }
}
