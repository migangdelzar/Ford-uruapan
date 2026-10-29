package com.ford.service.catalog.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** National model-family sales, never trim-level sales or inventory evidence. */
public record ModelPopularity(
        String modelFamily,
        List<String> catalogModelAliases,
        long cumulativeUnits,
        BigDecimal cumulativeSharePercent,
        SourceState cumulativeStatus,
        Long ytd2025Units,
        SourceState ytd2025Status,
        Long ytd2026Units,
        SourceState ytd2026Status,
        String origin,
        String geography,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate ytd2025Start,
        LocalDate ytd2025End,
        LocalDate ytd2026Start,
        LocalDate ytd2026End,
        String sourceLabel,
        String sourceUrl,
        LocalDate retrievalDate) {

    public enum SourceState {
        REPORTED,
        NOT_REPORTED_OR_NOT_MARKETED,
        NOT_AVAILABLE,
        NOT_DISCLOSED,
        STALE
    }

    public ModelPopularity {
        requireText(modelFamily, "modelFamily");
        if (catalogModelAliases == null || catalogModelAliases.isEmpty()) {
            throw new IllegalArgumentException("catalogModelAliases must not be empty");
        }
        catalogModelAliases.forEach(alias -> requireText(alias, "catalogModelAlias"));
        catalogModelAliases = List.copyOf(catalogModelAliases);
        if (cumulativeUnits < 0 || cumulativeSharePercent == null || cumulativeSharePercent.signum() < 0
                || cumulativeSharePercent.compareTo(BigDecimal.valueOf(100)) > 0
                || cumulativeStatus != SourceState.REPORTED) {
            throw new IllegalArgumentException("Cumulative units/share must be non-negative reported evidence");
        }
        validateMetric(ytd2025Units, ytd2025Status);
        validateMetric(ytd2026Units, ytd2026Status);
        requireText(origin, "origin");
        requireText(geography, "geography");
        requireText(sourceLabel, "sourceLabel");
        requireText(sourceUrl, "sourceUrl");
        var uri = java.net.URI.create(sourceUrl);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("sourceUrl must be an HTTPS URL");
        }
        validatePeriod(periodStart, periodEnd);
        validatePeriod(ytd2025Start, ytd2025End);
        validatePeriod(ytd2026Start, ytd2026End);
        if (ytd2025Start.isBefore(periodStart) || ytd2026Start.isBefore(periodStart)
                || ytd2025End.isAfter(periodEnd) || ytd2026End.isAfter(periodEnd)
                || !ytd2025End.isBefore(ytd2026Start)
                || retrievalDate == null || retrievalDate.isBefore(periodEnd)) {
            throw new IllegalArgumentException("YTD/retrieval dates must respect the cumulative period");
        }
    }

    private static void validateMetric(Long units, SourceState state) {
        if (state == null || (state == SourceState.REPORTED ? units == null || units < 0 : units != null)) {
            throw new IllegalArgumentException("Reported metrics need non-negative units; other states need null units");
        }
    }

    private static void validatePeriod(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("Metric period must have ordered start/end dates");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
