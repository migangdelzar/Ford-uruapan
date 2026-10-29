package com.ford.service.catalog.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Reviewed national parent-model sales; never trim sales or dealer inventory. */
public record ModelPopularityEvidence(
        String modelFamily,
        long cumulativeUnits,
        BigDecimal cumulativeSharePercent,
        LocalDate periodStart,
        LocalDate periodEnd,
        Long ytd2026Units,
        String ytd2026Status,
        String geography,
        String sourceLabel,
        String sourceUrl) { }
