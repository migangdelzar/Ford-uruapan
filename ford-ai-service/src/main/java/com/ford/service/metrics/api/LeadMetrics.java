package com.ford.service.metrics.api;

public record LeadMetrics(MetricPeriod period, long totalLeads, long hot, long warm, long cold,
                          long unknown) {
}
