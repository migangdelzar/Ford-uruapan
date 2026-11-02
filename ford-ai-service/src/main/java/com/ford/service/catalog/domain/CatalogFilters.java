package com.ford.service.catalog.domain;

import java.math.BigDecimal;

public record CatalogFilters(
        String model,
        Integer modelYear,
        BigDecimal minimumPrice,
        BigDecimal maximumPrice,
        String color,
        String category,
        String bodyType,
        String fuelType) {

    public static CatalogFilters empty() {
        return new CatalogFilters(null, null, null, null, null, null, null, null);
    }
}
