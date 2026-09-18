package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.application.port.out.EmbeddingPort;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Objects;

public final class SpringAiEmbeddingAdapter implements EmbeddingPort {

    private final EmbeddingModel model;
    private final String provider;
    private final String modelVersion;

    public SpringAiEmbeddingAdapter(EmbeddingModel model, String provider, String modelVersion) {
        this.model = Objects.requireNonNull(model);
        this.provider = Objects.requireNonNull(provider);
        this.modelVersion = Objects.requireNonNull(modelVersion);
    }

    @Override
    public float[] embed(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        return model.embed(content);
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String modelVersion() {
        return modelVersion;
    }
}
