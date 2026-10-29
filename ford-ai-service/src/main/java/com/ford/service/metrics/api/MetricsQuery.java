package com.ford.service.metrics.api;

import com.ford.service.channels.domain.Channel;

import java.time.Instant;
import java.util.UUID;

public record MetricsQuery(UUID tenantId, Instant from, Instant to, String timezone,
                           Channel channel, UUID advisorId, String groupBy) {
    public MetricsQuery {
        if (tenantId == null || from == null || to == null) {
            throw new IllegalArgumentException("tenant and metric period are required");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("metric period must have a positive duration");
        }
        if (to.minusSeconds(366L * 24 * 60 * 60).isAfter(from)) {
            throw new IllegalArgumentException("metric period cannot exceed 366 days");
        }
        timezone = timezone == null || timezone.isBlank() ? "UTC" : timezone;
        java.time.ZoneId.of(timezone);
        groupBy = groupBy == null || groupBy.isBlank() ? "day" : groupBy;
        if (!java.util.Set.of("day", "week", "month").contains(groupBy)) {
            throw new IllegalArgumentException("groupBy must be day, week, or month");
        }
    }
}
