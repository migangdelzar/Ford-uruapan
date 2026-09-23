package com.ford.service.metrics.adapter.in.web;

import com.ford.service.channels.domain.Channel;
import com.ford.service.metrics.api.AdvisorMetrics;
import com.ford.service.metrics.api.BotMetrics;
import com.ford.service.metrics.api.ChannelMetrics;
import com.ford.service.metrics.api.ConversationMetrics;
import com.ford.service.metrics.api.FunnelMetrics;
import com.ford.service.metrics.api.LeadMetrics;
import com.ford.service.metrics.api.MetricsOverview;
import com.ford.service.metrics.api.MetricsQuery;
import com.ford.service.metrics.api.TimeSeriesMetrics;
import com.ford.service.metrics.application.MetricsQueryService;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/metrics")
@PreAuthorize("hasAuthority('SCOPE_metrics:read') || hasRole('ADMIN')")
public class MetricsController {

    private final MetricsQueryService metrics;

    public MetricsController(MetricsQueryService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/overview")
    public MetricsOverview overview(@ModelAttribute Params params, Authentication authentication) {
        return metrics.overview(params.query(authentication));
    }

    @GetMapping("/bot")
    public BotMetrics bot(@ModelAttribute Params params, Authentication authentication) {
        return metrics.bot(params.query(authentication));
    }

    @GetMapping("/leads")
    public LeadMetrics leads(@ModelAttribute Params params, Authentication authentication) {
        return metrics.leads(params.query(authentication));
    }

    @GetMapping("/conversations")
    public ConversationMetrics conversations(@ModelAttribute Params params, Authentication authentication) {
        return metrics.conversations(params.query(authentication));
    }

    @GetMapping("/channels")
    public ChannelMetrics channels(@ModelAttribute Params params, Authentication authentication) {
        return metrics.channels(params.query(authentication));
    }

    @GetMapping("/advisors")
    public AdvisorMetrics advisors(@ModelAttribute Params params, Authentication authentication) {
        return metrics.advisors(params.query(authentication));
    }

    @GetMapping("/funnel")
    public FunnelMetrics funnel(@ModelAttribute Params params, Authentication authentication) {
        return metrics.funnel(params.query(authentication));
    }

    @GetMapping("/timeseries")
    public TimeSeriesMetrics timeseries(@ModelAttribute Params params, Authentication authentication) {
        return metrics.timeseries(params.query(authentication));
    }

    public record Params(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "UTC") String timezone,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) UUID advisorId,
            @RequestParam(defaultValue = "day") String groupBy,
            @RequestParam(required = false) UUID tenantId) {

        MetricsQuery query(Authentication authentication) {
            var operator = AuthenticatedOperator.from(authentication);
            var selectedTenant = tenantId;
            if (selectedTenant == null) {
                if (operator.tenantIds().size() != 1) {
                    throw new IllegalArgumentException("tenantId is required when operator has multiple tenants");
                }
                selectedTenant = operator.tenantIds().iterator().next();
            } else if (!operator.canAccess(selectedTenant)) {
                throw new SecurityException("operator is not authorized for tenant");
            }
            var end = to == null || to.isBlank() ? Instant.now() : parseInstant(to, "to");
            var start = from == null || from.isBlank() ? end.minus(30, ChronoUnit.DAYS) : parseInstant(from, "from");
            return new MetricsQuery(selectedTenant, start, end, timezone, channel, advisorId, groupBy);
        }

        private static Instant parseInstant(String value, String name) {
            try {
                return Instant.parse(value);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(name + " must be an ISO-8601 instant", exception);
            }
        }
    }
}
