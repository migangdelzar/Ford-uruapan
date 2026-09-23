package com.ford.service.metrics.api;

import java.util.Map;

public record ChannelMetrics(MetricPeriod period, Map<String, Long> byChannel) {
    public ChannelMetrics {
        byChannel = Map.copyOf(byChannel == null ? Map.of() : byChannel);
    }
}
