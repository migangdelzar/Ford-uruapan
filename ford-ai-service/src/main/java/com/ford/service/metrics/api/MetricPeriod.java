package com.ford.service.metrics.api;

import java.time.Instant;

public record MetricPeriod(Instant from, Instant to, String timezone) {
}
