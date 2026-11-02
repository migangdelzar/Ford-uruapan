package com.ford.service.metrics.api;

import java.util.Map;

public record ConversationMetrics(MetricPeriod period, Map<String, Long> byState) {
    public ConversationMetrics {
        byState = Map.copyOf(byState == null ? Map.of() : byState);
    }
}
