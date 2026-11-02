package com.ford.service.catalog.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record CatalogFact(
        UUID modelYearId,
        UUID variantId,
        String model,
        Integer modelYear,
        String variant,
        BigDecimal price,
        String currency,
        String color,
        String sourceUrl) {
}
