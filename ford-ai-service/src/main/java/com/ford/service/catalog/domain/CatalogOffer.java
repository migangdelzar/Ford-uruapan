package com.ford.service.catalog.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CatalogOffer(
        UUID id,
        String model,
        Integer modelYear,
        String title,
        String description,
        BigDecimal cashPrice,
        String currency,
        LocalDate validFrom,
        LocalDate validUntil,
        String sourceUrl) {
}
