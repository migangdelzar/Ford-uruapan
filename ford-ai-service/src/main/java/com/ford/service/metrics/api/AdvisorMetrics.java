package com.ford.service.metrics.api;

import java.util.List;
import java.util.UUID;

public record AdvisorMetrics(MetricPeriod period, List<AdvisorMetric> advisors) {
    public AdvisorMetrics {
        advisors = List.copyOf(advisors == null ? List.of() : advisors);
    }

    public record AdvisorMetric(UUID advisorId, long activeHandoffs, long closedHandoffs) {
    }
}
