package com.ford.service.team;

import com.ford.service.config.SecurityConfiguration;
import com.ford.service.team.adapter.in.web.AdvisorController;
import com.ford.service.team.api.AdvisorView;
import com.ford.service.team.application.AdvisorQueryService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdvisorController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class AdvisorControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean AdvisorQueryService advisors;

    @Test
    void requiresAdvisorReadAuthority() throws Exception {
        mockMvc.perform(get("/api/v1/team/advisors")
                        .with(jwt().jwt(token -> token.subject("operator-1"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnsTenantScopedAdvisorViews() throws Exception {
        var tenantId = UUID.randomUUID();
        var operatorId = UUID.randomUUID();
        when(advisors.list(any(), any())).thenReturn(List.of(
                new AdvisorView(operatorId, null, null, "operator", null,
                        AdvisorView.Status.AVAILABLE, null, 2)));

        mockMvc.perform(get("/api/v1/team/advisors")
                        .param("tenantId", tenantId.toString())
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", tenantId.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assignedCount").value(2))
                .andExpect(jsonPath("$[0].status").value("available"));
    }
}
