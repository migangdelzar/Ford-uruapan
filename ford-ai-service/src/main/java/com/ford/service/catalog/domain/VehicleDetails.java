package com.ford.service.catalog.domain;

import java.util.Map;
import java.util.UUID;

public record VehicleDetails(UUID id, VehicleSummary summary, Map<String, String> specifications) {

    public VehicleDetails {
        specifications = Map.copyOf(specifications);
    }
}
