package com.ford.service.dealership;

import com.ford.service.config.SecurityConfiguration;
import com.ford.service.dealership.adapter.in.web.DealershipController;
import com.ford.service.dealership.application.port.out.DealershipPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DealershipController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class DealershipControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean DealershipPort dealership;

    @Test
    void rejectsInventoryWithoutInventoryAuthority() throws Exception {
        mockMvc.perform(get("/api/v1/inventory")
                        .param("tenantId", "11111111-1111-1111-1111-111111111111")
                        .with(jwt().jwt(token -> token.subject("operator-1"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnsTypedUnavailableWhenUpstreamIsNotConfigured() throws Exception {
        when(dealership.inventory(any(), any())).thenThrow(
                new com.ford.service.shared.UpstreamUnavailableException("dealership API is not configured"));

        mockMvc.perform(get("/api/v1/inventory")
                        .param("tenantId", "11111111-1111-1111-1111-111111111111")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", "11111111-1111-1111-1111-111111111111"))
                                .authorities(() -> "SCOPE_inventory:read")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void protectsLeadSyncWithWriteAuthority() throws Exception {
        mockMvc.perform(post("/api/v1/leads/" + java.util.UUID.randomUUID() + "/sync")
                        .param("tenantId", "11111111-1111-1111-1111-111111111111")
                        .with(jwt().jwt(token -> token.subject("operator-1"))))
                .andExpect(status().isForbidden());
    }
}
