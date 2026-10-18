package com.ford.service.catalog.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record VehicleEvidence(String model, Integer year, String trim, BigDecimal price,
                                String currency, String modelPageUrl, String priceSourceUrl,
                                Instant observedAt, List<VehicleFact> facts,
                                List<VehicleImage> images,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String technicalSheetUrl,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String comparisonUrl) {
    public VehicleEvidence(String model, Integer year, String trim, BigDecimal price,
                              String currency, String modelPageUrl, String priceSourceUrl,
                              Instant observedAt, List<VehicleFact> facts,
                              List<VehicleImage> images) {
        this(model, year, trim, price, currency, modelPageUrl, priceSourceUrl,
                observedAt, facts, images, null, null);
    }

    public VehicleEvidence(String model, Integer year, String trim, BigDecimal price,
                              String currency, String modelPageUrl, String priceSourceUrl,
                              Instant observedAt, List<VehicleFact> facts,
                              List<VehicleImage> images, String technicalSheetUrl) {
        this(model, year, trim, price, currency, modelPageUrl, priceSourceUrl,
                observedAt, facts, images, technicalSheetUrl, null);
    }

    public VehicleEvidence(String model, Integer year, String trim, BigDecimal price,
                              String currency, String modelPageUrl, String priceSourceUrl,
                              Instant observedAt, List<VehicleFact> facts) {
        this(model, year, trim, price, currency, modelPageUrl, priceSourceUrl,
                observedAt, facts, List.of());
    }

    public VehicleEvidence {
        facts = facts == null ? List.of() : List.copyOf(facts);
        images = images == null ? List.of() : List.copyOf(images);
    }
}
