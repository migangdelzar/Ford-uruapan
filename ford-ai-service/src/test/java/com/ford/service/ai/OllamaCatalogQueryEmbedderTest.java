package com.ford.service.ai;

import com.ford.service.ai.adapter.out.springai.OllamaCatalogQueryEmbedder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class OllamaCatalogQueryEmbedderTest {

    @Test
    void requestsTheStoredBgeM3ModelAndReturnsIts1024Values() {
        var api = mock(OllamaApi.class);
        var vector = vector();
        when(api.embed(any(OllamaApi.EmbeddingsRequest.class))).thenReturn(
                new OllamaApi.EmbeddingsResponse("bge-m3", List.of(vector), 1L, 1L, 1));
        var embedder = new OllamaCatalogQueryEmbedder(api, "bge-m3");

        var result = embedder.embed("SUV compacta familiar");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().storedModel()).isEqualTo("bge-m3");
        assertThat(result.orElseThrow().values()).hasSize(1024).first().isEqualTo(1.0f);
        var request = org.mockito.ArgumentCaptor.forClass(OllamaApi.EmbeddingsRequest.class);
        verify(api).embed(request.capture());
        assertThat(request.getValue().model()).isEqualTo("bge-m3");
        assertThat(request.getValue().input()).containsExactly("SUV compacta familiar");
    }

    @Test
    void reportsMismatchedModelOrDimensionsAsUnavailable() {
        var wrongModelApi = mock(OllamaApi.class);
        when(wrongModelApi.embed(any(OllamaApi.EmbeddingsRequest.class))).thenReturn(
                new OllamaApi.EmbeddingsResponse("nomic-embed-text", List.of(vector()), 1L, 1L, 1));
        assertThat(new OllamaCatalogQueryEmbedder(wrongModelApi, "bge-m3")
                .embed("Ranger familiar")).isEmpty();

        var wrongDimensionApi = mock(OllamaApi.class);
        when(wrongDimensionApi.embed(any(OllamaApi.EmbeddingsRequest.class))).thenReturn(
                new OllamaApi.EmbeddingsResponse("bge-m3", List.of(new float[768]), 1L, 1L, 1));
        assertThat(new OllamaCatalogQueryEmbedder(wrongDimensionApi, "bge-m3")
                .embed("Ranger familiar")).isEmpty();
    }

    @Test
    void hidesQueryTextAndProviderErrorDetailsWhenEmbeddingFails(CapturedOutput output) {
        var api = mock(OllamaApi.class);
        when(api.embed(any(OllamaApi.EmbeddingsRequest.class)))
                .thenThrow(new IllegalStateException("private endpoint secret and query"));
        var result = new OllamaCatalogQueryEmbedder(api, "bge-m3")
                .embed("private customer query");

        assertThat(result).isEmpty();
        assertThat(output.getOut())
                .contains("catalog query embedding unavailable")
                .doesNotContain("private endpoint secret", "private customer query");
    }

    private static float[] vector() {
        var values = new float[1024];
        values[0] = 1.0f;
        return values;
    }
}
