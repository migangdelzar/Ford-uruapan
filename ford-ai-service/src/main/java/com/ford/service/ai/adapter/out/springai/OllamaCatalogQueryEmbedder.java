package com.ford.service.ai.adapter.out.springai;

import com.ford.service.catalog.api.CatalogQueryEmbedder;
import com.ford.service.catalog.api.CatalogQueryVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

/** Requests BGE-M3 vectors through the existing Access-configured Ollama API client. */
public final class OllamaCatalogQueryEmbedder implements CatalogQueryEmbedder {

    private static final Logger log = LoggerFactory.getLogger(OllamaCatalogQueryEmbedder.class);

    private final OllamaApi api;
    private final String storedModel;

    public OllamaCatalogQueryEmbedder(OllamaApi api, String storedModel) {
        this.api = Objects.requireNonNull(api, "Ollama API is required");
        this.storedModel = Objects.requireNonNull(storedModel, "catalog embedding model is required");
    }

    @Override
    public Optional<CatalogQueryVector> embed(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        try {
            var response = api.embed(new OllamaApi.EmbeddingsRequest(storedModel, query));
            if (response == null || !storedModel.equals(response.model())) {
                log.warn("catalog query embedding unavailable: failureCategory=model_mismatch");
                return Optional.empty();
            }
            if (response.embeddings() == null || response.embeddings().isEmpty()) {
                log.warn("catalog query embedding unavailable: failureCategory=empty_response");
                return Optional.empty();
            }
            var raw = response.embeddings().getFirst();
            var values = new ArrayList<Float>(raw.length);
            for (var value : raw) values.add(value);
            return Optional.of(new CatalogQueryVector(storedModel, values));
        } catch (RuntimeException failure) {
            log.warn("catalog query embedding unavailable: failureCategory={} exceptionType={}",
                    failureCategory(failure), failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String failureCategory(RuntimeException failure) {
        var type = failure.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        if (type.contains("timeout")) return "timeout";
        if (type.contains("unauthorized") || type.contains("forbidden")) return "authentication";
        if (type.contains("connect") || type.contains("resourceaccess")) return "network";
        return "source_unavailable";
    }
}
