package com.ford.service.catalog.api;

import java.util.List;
import org.springframework.modulith.NamedInterface;

/** Query embedding contract aligned to Ford-scraping's stored catalog vectors. */
@NamedInterface
public record CatalogQueryVector(String storedModel, List<Float> values) {

    public static final String STORED_MODEL = "bge-m3";
    public static final int DIMENSIONS = 1024;

    public CatalogQueryVector {
        if (!STORED_MODEL.equals(storedModel)) {
            throw new IllegalArgumentException("catalog query vector model must be bge-m3");
        }
        if (values == null || values.size() != DIMENSIONS
                || values.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
            throw new IllegalArgumentException("catalog query vector must contain 1024 finite values");
        }
        values = List.copyOf(values);
    }
}
