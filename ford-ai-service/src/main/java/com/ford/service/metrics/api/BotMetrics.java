package com.ford.service.metrics.api;

public record BotMetrics(MetricPeriod period, long classifiedDecisions, long fallbackDecisions,
                         long handoffRecommendations, double averageLatencyMs,
                         long successfulProviderCalls, long failedProviderCalls) {
}
