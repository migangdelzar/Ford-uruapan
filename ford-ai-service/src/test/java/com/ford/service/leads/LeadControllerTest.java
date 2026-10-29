package com.ford.service.leads;

import com.ford.service.config.SecurityConfiguration;
import com.ford.service.leads.adapter.in.web.LeadController;
import com.ford.service.leads.api.LeadPage;
import com.ford.service.leads.api.LeadUpdateRequest;
import com.ford.service.leads.api.LeadView;
import com.ford.service.leads.application.LeadCommandService;
import com.ford.service.leads.application.LeadQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(LeadController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class LeadControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    @Autowired MockMvc mockMvc;
    @MockitoBean LeadQueryService leads;
    @MockitoBean LeadCommandService commands;

    @Test
    void listsTenantScopedLeads() throws Exception {
        when(leads.list(any(), any(), any(Integer.class), any())).thenReturn(new LeadPage(List.of(), null, false));
        mockMvc.perform(get("/api/v1/leads").with(jwt().jwt(token -> token.subject("operator-1")
                        .claim("tenant_id", TENANT.toString())).authorities(() -> "SCOPE_leads:read")))
                .andExpect(status().isOk());
    }

    @Test
    void updatesLeadWithVersionAndIdempotencyHeaders() throws Exception {
        var leadId = UUID.randomUUID();
        var view = new LeadView(leadId, TENANT, UUID.randomUUID(), "Name", null, null, null, null,
                "hot", 80, java.time.Instant.now(), 2);
        when(commands.update(any(), any(), eq(leadId), eq(1L), eq("lead-key"), any(LeadUpdateRequest.class)))
                .thenReturn(view);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/leads/" + leadId)
                        .header("Idempotency-Key", "lead-key")
                        .header("If-Match", "\"v1\"")
                        .contentType("application/json")
                        .content("{\"name\":\"Name\",\"temperature\":\"hot\",\"temperatureSource\":\"HUMAN\"}")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", TENANT.toString())).authorities(() -> "SCOPE_leads:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }
}
