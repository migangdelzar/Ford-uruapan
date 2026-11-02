package com.ford.service.metrics.api;

public record FunnelMetrics(MetricPeriod period, long conversations, long leads,
                            long hotLeads, long handoffs, long appointments) {
}
