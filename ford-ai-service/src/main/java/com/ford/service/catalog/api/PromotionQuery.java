package com.ford.service.catalog.api;

import java.time.LocalDate;

public record PromotionQuery(String model, Integer modelYear, LocalDate asOf,
                                boolean activeOnly, int limit, int offset) {
    public PromotionQuery(String model, Integer modelYear, LocalDate asOf, boolean activeOnly, int limit) {
        this(model, modelYear, asOf, activeOnly, limit, 0);
    }

    public PromotionQuery {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("promotion search limit must be from 1 to 20");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("promotion search offset must not be negative");
        }
    }
}
