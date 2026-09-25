package com.ford.service.metrics.application;

import com.ford.service.metrics.api.AdvisorMetrics;
import com.ford.service.metrics.api.BotMetrics;
import com.ford.service.metrics.api.ChannelMetrics;
import com.ford.service.metrics.api.ConversationMetrics;
import com.ford.service.metrics.api.FunnelMetrics;
import com.ford.service.metrics.api.LeadMetrics;
import com.ford.service.metrics.api.MetricsOverview;
import com.ford.service.metrics.api.MetricsQuery;
import com.ford.service.metrics.api.TimeSeriesMetrics;

public interface MetricsQueryService {
    MetricsOverview overview(MetricsQuery query);
    BotMetrics bot(MetricsQuery query);
    LeadMetrics leads(MetricsQuery query);
    ConversationMetrics conversations(MetricsQuery query);
    ChannelMetrics channels(MetricsQuery query);
    AdvisorMetrics advisors(MetricsQuery query);
    FunnelMetrics funnel(MetricsQuery query);
    TimeSeriesMetrics timeseries(MetricsQuery query);
}
