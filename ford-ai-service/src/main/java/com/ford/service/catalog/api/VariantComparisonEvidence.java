package com.ford.service.catalog.api;

import java.time.Instant;

public record VariantComparisonEvidence(String model, Integer year, String trim,
                                           String competitorMake, String competitorModel,
                                           Integer competitorYear, String competitorTrim,
                                           String category, String factName, String factValue,
                                           String factUnit, String sourceUrl, Instant observedAt,
                                           String comparisonUrl) {
    public VariantComparisonEvidence(String model, Integer year, String trim,
                                        String competitorMake, String competitorModel,
                                        Integer competitorYear, String competitorTrim,
                                        String category, String factName, String factValue,
                                        String factUnit, String sourceUrl, Instant observedAt) {
        this(model, year, trim, competitorMake, competitorModel, competitorYear, competitorTrim,
                category, factName, factValue, factUnit, sourceUrl, observedAt, sourceUrl);
    }
}
