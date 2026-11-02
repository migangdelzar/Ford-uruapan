package com.ford.service.conversations;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LocalBgeOllamaContainerTest {
    @Container
    static final LocalBgeOllamaContainer OLLAMA = new LocalBgeOllamaContainer();

    @Test
    void servesBgeModelFromTheReadOnlyHostCache() {
        assertThat(OLLAMA.baseUrl()).startsWith("http://localhost:");
        assertThat(OLLAMA.hasBgeModel()).isTrue();
    }

    @Test
    void generatesAQueryVectorFromTheMountedBgeModel() {
        var response = OllamaApi.builder().baseUrl(OLLAMA.baseUrl()).build()
                .embed(new OllamaApi.EmbeddingsRequest("bge-m3", "pickup familiar Ranger"));

        assertThat(response).isNotNull();
        assertThat(response.model()).startsWith("bge-m3");
        assertThat(response.embeddings()).hasSize(1);
        assertThat(response.embeddings().getFirst()).hasSize(1024);
    }
}
