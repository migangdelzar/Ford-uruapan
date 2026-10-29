package com.ford.service.catalog.api;

import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;

public record PromotionEvidence(String id, String model, Integer modelYear, String title,
                                  String description, BigDecimal discountAmount,
                                  BigDecimal discountPercent, BigDecimal cashPrice,
                                  String currency, String conditions,
                                  LocalDate validFrom, LocalDate validUntil,
                                  String validityStatus, String sourceUrl,
                                  Instant observedAt, String modelUrl, JsonNode benefits, JsonNode termConflict) {
    public PromotionEvidence(String id, String model, Integer modelYear, String title,
                                String description, BigDecimal discountAmount, BigDecimal discountPercent,
                                BigDecimal cashPrice, String currency, String conditions,
                                LocalDate validFrom, LocalDate validUntil, String validityStatus,
                                String sourceUrl, Instant observedAt, String modelUrl) {
        this(id, model, modelYear, title, description, discountAmount, discountPercent, cashPrice, currency,
                conditions, validFrom, validUntil, validityStatus, sourceUrl, observedAt, modelUrl, null, null);
    }
}
