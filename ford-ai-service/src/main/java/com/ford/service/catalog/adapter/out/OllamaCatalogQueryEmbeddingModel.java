package com.ford.service.catalog.adapter.out;

import com.ford.service.catalog.domain.CatalogQueryEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Arrays;
import java.util.Objects;

public final class OllamaCatalogQueryEmbeddingModel implements CatalogQueryEmbeddingModel {

    public static final int DIMENSIONS = 1024;
    private final EmbeddingModel delegate;

    public OllamaCatalogQueryEmbeddingModel(EmbeddingModel delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Catalog embedding query must not be blank");
        }
        var vector = delegate.embed(text);
        if (vector == null || vector.length != DIMENSIONS) {
            throw new IllegalStateException("Catalog query embedding must have exactly 1024 dimensions");
        }
        if (Arrays.stream(toDoubleArray(vector)).anyMatch(value -> !Double.isFinite(value))) {
            throw new IllegalStateException("Catalog query embedding must contain only finite values");
        }
        return vector;
    }

    private static double[] toDoubleArray(float[] values) {
        var result = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index];
        }
        return result;
    }
}
