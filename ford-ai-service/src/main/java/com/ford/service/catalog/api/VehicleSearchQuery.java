package com.ford.service.catalog.api;

import java.math.BigDecimal;

public record VehicleSearchQuery(String query, String model, Integer modelYear,
                                    BigDecimal minPrice, BigDecimal maxPrice,
                                    String category, String bodyType, String fuelType,
                                    int limit) {
    public VehicleSearchQuery {
        if (limit < 1 || limit > 5) {
            throw new IllegalArgumentException("catalog search limit must be from 1 to 5");
        }
    }
}
