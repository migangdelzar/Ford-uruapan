package com.ford.service.metrics.api;

import java.time.Instant;
import java.util.List;

public record TimeSeriesMetrics(MetricPeriod period, String groupBy, List<MetricPoint> points) {
    public TimeSeriesMetrics {
        points = List.copyOf(points == null ? List.of() : points);
    }

    public record MetricPoint(Instant bucket, long conversations, long inboundMessages,
                              long outboundMessages, long handoffs, long failedDeliveries) {
    }
}
