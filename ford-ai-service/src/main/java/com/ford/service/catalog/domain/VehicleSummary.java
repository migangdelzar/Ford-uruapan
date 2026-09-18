package com.ford.service.catalog.domain;

import com.ford.service.shared.FreshnessStatus;
import com.ford.service.shared.Money;

import java.util.List;
import java.util.UUID;

public record VehicleSummary(
        UUID id,
        String modelName,
        String variantName,
        int modelYear,
        List<String> colors,
        Money currentPrice,
        FreshnessStatus freshnessStatus) {

    public VehicleSummary {
        colors = List.copyOf(colors);
    }
}
