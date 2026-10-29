package com.ford.service.catalog.api;

import java.time.Instant;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VehicleFact(String name, String value, String unit, String sourceUrl,
                             Instant observedAt, String type, String variantId, String category,
                             String normalizedName, BigDecimal numericValue) {
    public VehicleFact(String name, String value, String unit, String sourceUrl,
                          Instant observedAt) {
        this(name, value, unit, sourceUrl, observedAt, "specification", null, null, null, null);
    }

    public VehicleFact(String name, String value, String unit, String sourceUrl,
                          Instant observedAt, String type) {
        this(name, value, unit, sourceUrl, observedAt, type, null, null, null, null);
    }

    public VehicleFact {
        type = type == null || type.isBlank() ? "specification" : type;
    }
}
