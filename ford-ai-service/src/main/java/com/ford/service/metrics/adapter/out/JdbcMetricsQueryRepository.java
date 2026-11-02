package com.ford.service.metrics.adapter.out;

import com.ford.service.channels.domain.Channel;
import com.ford.service.metrics.api.AdvisorMetrics;
import com.ford.service.metrics.api.BotMetrics;
import com.ford.service.metrics.api.ChannelMetrics;
import com.ford.service.metrics.api.ConversationMetrics;
import com.ford.service.metrics.api.FunnelMetrics;
import com.ford.service.metrics.api.LeadMetrics;
import com.ford.service.metrics.api.MetricPeriod;
import com.ford.service.metrics.api.MetricsOverview;
import com.ford.service.metrics.api.MetricsQuery;
import com.ford.service.metrics.api.TimeSeriesMetrics;
import com.ford.service.metrics.application.MetricsQueryService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcMetricsQueryRepository implements MetricsQueryService {

    private final JdbcClient jdbc;

    public JdbcMetricsQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public MetricsOverview overview(MetricsQuery query) {
        var conversation = jdbc.sql("""
                select count(*) as total,
                       count(*) filter (where state <> 'CLOSED') as active
                from conversation
                where tenant_id = :tenantId and updated_at >= :from and updated_at < :to
                """)
                .param("tenantId", query.tenantId()).param("from", Timestamp.from(query.from()))
                .param("to", Timestamp.from(query.to()))
                .query((rs, row) -> new long[]{rs.getLong("total"), rs.getLong("active")}).single();
        var messages = jdbc.sql("""
                select count(*) filter (where m.direction = 'INBOUND') as inbound,
                       count(*) filter (where m.direction = 'OUTBOUND') as outbound
                from conversation_message m join conversation c on c.id = m.conversation_id
                where c.tenant_id = :tenantId and m.received_at >= :from and m.received_at < :to
                """)
                .param("tenantId", query.tenantId()).param("from", Timestamp.from(query.from()))
                .param("to", Timestamp.from(query.to()))
                .query((rs, row) -> new long[]{rs.getLong("inbound"), rs.getLong("outbound")}).single();
        var handoffs = scalar("select count(*) from handoff_ticket where tenant_id = :tenantId and created_at >= :from and created_at < :to", query);
        var failed = scalar("select count(*) from outbox_event where tenant_id = :tenantId and status = 'DEAD_LETTER' and available_at >= :from and available_at < :to", query);
        return new MetricsOverview(period(query), conversation[0], conversation[1], messages[0], messages[1], handoffs, failed);
    }

    @Override
    public BotMetrics bot(MetricsQuery query) {
        var values = jdbc.sql("""
                select count(*) as classified,
                       count(*) filter (where fallback_used) as fallback,
                       coalesce(avg(latency_ms), 0) as latency,
                       count(*) filter (where provider <> 'rules') as provider_calls
                from conversation_decision d join conversation c on c.id = d.conversation_id
                where c.tenant_id = :tenantId and d.created_at >= :from and d.created_at < :to
                """)
                .param("tenantId", query.tenantId()).param("from", Timestamp.from(query.from()))
                .param("to", Timestamp.from(query.to()))
                .query((rs, row) -> new Object[]{rs.getLong("classified"), rs.getLong("fallback"),
                        rs.getDouble("latency"), rs.getLong("provider_calls")}).single();
        var handoffRecommendations = scalar("""
                select count(*) from handoff_ticket
                where tenant_id = :tenantId and reason like '%assistant%'
                  and created_at >= :from and created_at < :to
                """, query);
        return new BotMetrics(period(query), (Long) values[0], (Long) values[1], handoffRecommendations,
                (Double) values[2], (Long) values[3], 0);
    }

    @Override
    public LeadMetrics leads(MetricsQuery query) {
        var values = jdbc.sql("""
                select count(*) as total,
                       count(*) filter (where upper(coalesce(latest.band, 'UNKNOWN')) = 'HOT') as hot,
                       count(*) filter (where upper(coalesce(latest.band, 'UNKNOWN')) = 'WARM') as warm,
                       count(*) filter (where upper(coalesce(latest.band, 'UNKNOWN')) = 'COLD') as cold
                from lead l
                join conversation c on c.id = l.conversation_id and c.tenant_id = l.tenant_id
                left join lateral (
                    select band from lead_score where lead_id = l.id order by created_at desc limit 1
                ) latest on true
                where l.tenant_id = :tenantId and l.updated_at >= :from and l.updated_at < :to
                """)
                .param("tenantId", query.tenantId()).param("from", Timestamp.from(query.from()))
                .param("to", Timestamp.from(query.to()))
                .query((rs, row) -> new long[]{rs.getLong("total"), rs.getLong("hot"),
                        rs.getLong("warm"), rs.getLong("cold")}).single();
        return new LeadMetrics(period(query), values[0], values[1], values[2], values[3],
                Math.max(0, values[0] - values[1] - values[2] - values[3]));
    }

    @Override
    public ConversationMetrics conversations(MetricsQuery query) {
        var rows = jdbc.sql("""
                select state, count(*) as total from conversation
                where tenant_id = :tenantId and updated_at >= :from and updated_at < :to
                group by state order by state
                """)
                .param("tenantId", query.tenantId()).param("from", Timestamp.from(query.from()))
                .param("to", Timestamp.from(query.to()))
                .query((rs, row) -> Map.entry(rs.getString("state"), rs.getLong("total"))).list();
        return new ConversationMetrics(period(query), toMap(rows));
    }

    @Override
    public ChannelMetrics channels(MetricsQuery query) {
        var rows = jdbc.sql("""
                select channel, count(*) as total from conversation
                where tenant_id = :tenantId and updated_at >= :from and updated_at < :to
                group by channel order by channel
                """)
                .param("tenantId", query.tenantId()).param("from", Timestamp.from(query.from()))
                .param("to", Timestamp.from(query.to()))
                .query((rs, row) -> Map.entry(rs.getString("channel"), rs.getLong("total"))).list();
        return new ChannelMetrics(period(query), toMap(rows));
    }

    @Override
    public AdvisorMetrics advisors(MetricsQuery query) {
        var rows = jdbc.sql("""
                select assigned_operator_id,
                       count(*) filter (where status = 'HUMAN_ACTIVE') as active,
                       count(*) filter (where status = 'CLOSED') as closed
                from handoff_ticket
                where tenant_id = :tenantId and created_at >= :from and created_at < :to
                  and assigned_operator_id is not null
                group by assigned_operator_id order by assigned_operator_id
                """)
                .param("tenantId", query.tenantId()).param("from", Timestamp.from(query.from()))
                .param("to", Timestamp.from(query.to()))
                .query((rs, row) -> new AdvisorMetrics.AdvisorMetric(
                        rs.getObject("assigned_operator_id", UUID.class), rs.getLong("active"), rs.getLong("closed"))).list();
        return new AdvisorMetrics(period(query), rows);
    }

    @Override
    public FunnelMetrics funnel(MetricsQuery query) {
        var overview = overview(query);
        var leads = leads(query);
        return new FunnelMetrics(period(query), overview.totalConversations(), leads.totalLeads(), leads.hot(),
                overview.handoffs(), 0);
    }

    @Override
    public TimeSeriesMetrics timeseries(MetricsQuery query) {
        var bucket = switch (query.groupBy()) {
            case "week" -> "week";
            case "month" -> "month";
            default -> "day";
        };
        var rows = jdbc.sql("""
                select date_trunc(:bucket, c.updated_at) as bucket, count(*) as conversations,
                       0::bigint as inbound, 0::bigint as outbound, 0::bigint as handoffs, 0::bigint as failed
                from conversation c
                where c.tenant_id = :tenantId and c.updated_at >= :from and c.updated_at < :to
                group by date_trunc(:bucket, c.updated_at)
                order by bucket
                """)
                .param("bucket", bucket).param("tenantId", query.tenantId())
                .param("from", Timestamp.from(query.from())).param("to", Timestamp.from(query.to()))
                .query((rs, row) -> new TimeSeriesMetrics.MetricPoint(
                        rs.getTimestamp("bucket").toInstant(), rs.getLong("conversations"),
                        rs.getLong("inbound"), rs.getLong("outbound"), rs.getLong("handoffs"), rs.getLong("failed"))).list();
        return new TimeSeriesMetrics(period(query), bucket, rows);
    }

    private long scalar(String sql, MetricsQuery query) {
        return jdbc.sql(sql).param("tenantId", query.tenantId()).param("from", Timestamp.from(query.from()))
                .param("to", Timestamp.from(query.to())).query(Long.class).single();
    }

    private static MetricPeriod period(MetricsQuery query) {
        return new MetricPeriod(query.from(), query.to(), query.timezone());
    }

    private static Map<String, Long> toMap(List<Map.Entry<String, Long>> rows) {
        var result = new LinkedHashMap<String, Long>();
        rows.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
