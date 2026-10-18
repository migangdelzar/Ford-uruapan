package com.ford.service.catalog.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OfferQuery(String model, Integer modelYear, String offerType, String provider,
                            BigDecimal maxCashPrice, BigDecimal maxMonthlyPayment,
                            BigDecimal maxDownPayment, Integer minimumMonthsWithoutInterest,
                            Integer termMonths, Boolean activeOnly, LocalDate asOf, int limit) {
    public OfferQuery {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("offer search limit must be from 1 to 50");
        }
    }
}
