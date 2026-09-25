package com.ford.service.metrics;

import com.ford.service.config.SecurityConfiguration;
import com.ford.service.metrics.adapter.in.web.MetricsController;
import com.ford.service.metrics.api.MetricPeriod;
import com.ford.service.metrics.api.MetricsOverview;
import com.ford.service.metrics.application.MetricsQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetricsController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class MetricsControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MetricsQueryService metrics;

    @Test
    void returnsOverviewForMetricsAuthority() throws Exception {
        when(metrics.overview(any())).thenReturn(new MetricsOverview(
                new MetricPeriod(Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-10-01T00:00:00Z"), "UTC"),
                3, 2, 4, 5, 1, 0));

        mockMvc.perform(get("/api/v1/metrics/overview")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-10-01T00:00:00Z")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_metrics:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalConversations").value(3));
    }

    @Test
    void rejectsMetricsWithoutMetricsAuthority() throws Exception {
        mockMvc.perform(get("/api/v1/metrics/overview")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", TENANT.toString()))))
                .andExpect(status().isForbidden());
    }
}
