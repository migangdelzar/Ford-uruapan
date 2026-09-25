package com.ford.service.metrics.api;

public record MetricsOverview(MetricPeriod period, long totalConversations, long activeConversations,
                              long inboundMessages, long outboundMessages, long handoffs,
                              long failedDeliveries) {
}
