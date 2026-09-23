package com.ford.service.leads;

import com.ford.service.config.SecurityConfiguration;
import com.ford.service.leads.adapter.in.web.LeadController;
import com.ford.service.leads.api.LeadPage;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LeadController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class LeadControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    @Autowired MockMvc mockMvc;
    @MockitoBean LeadQueryService leads;

    @Test
    void listsTenantScopedLeads() throws Exception {
        when(leads.list(any(), any(), any(Integer.class), any())).thenReturn(new LeadPage(List.of(), null, false));
        mockMvc.perform(get("/api/v1/leads").with(jwt().jwt(token -> token.subject("operator-1")
                        .claim("tenant_id", TENANT.toString())).authorities(() -> "SCOPE_leads:read")))
                .andExpect(status().isOk());
    }
}
