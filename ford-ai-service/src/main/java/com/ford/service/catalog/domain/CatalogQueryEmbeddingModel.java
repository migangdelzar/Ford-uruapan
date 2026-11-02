package com.ford.service.catalog.domain;

@FunctionalInterface
public interface CatalogQueryEmbeddingModel {
    float[] embed(String text);
}
