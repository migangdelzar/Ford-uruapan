package com.ford.service.ai.configuration;

import com.ford.service.catalog.api.CatalogQueryVector;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.catalog.embedding")
public class CatalogEmbeddingProperties {

    private String model = CatalogQueryVector.STORED_MODEL;

    public String model() {
        return model;
    }

    public void setModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("catalog embedding model is required");
        }
        this.model = model.trim();
    }
}
