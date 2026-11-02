package com.ford.service.channels;

import com.ford.service.channels.adapter.in.web.MetaWindowController;
import com.ford.service.channels.application.port.out.CustomerWindowPort;
import com.ford.service.channels.application.port.out.TemplateMessagePort;
import com.ford.service.channels.domain.Channel;
import com.ford.service.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetaWindowController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class MetaWindowControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean CustomerWindowPort windows;
    @MockitoBean TemplateMessagePort templates;

    @Test
    void returnsWindowStateForAuthorizedTenant() throws Exception {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var expires = Instant.now().plusSeconds(3600);
        when(windows.find(tenant, conversation)).thenReturn(java.util.Optional.of(
                new CustomerWindowPort.CustomerWindow(tenant, conversation, Channel.WHATSAPP,
                        Instant.now(), expires)));

        mockMvc.perform(get("/api/v1/conversations/" + conversation + "/window")
                        .param("tenantId", tenant.toString())
                        .with(jwt().jwt(token -> token.subject("operator-1").claim("tenant_id", tenant.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true));
    }

    @Test
    void rejectsInvalidTemplateBeforeCallingProvider() throws Exception {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/conversations/" + conversation + "/hsm")
                        .param("tenantId", tenant.toString())
                        .header("Idempotency-Key", "template-1")
                        .contentType("application/json")
                        .content("{\"templateName\":\"bad name\",\"language\":\"es_MX\",\"parameters\":[]}")
                        .with(jwt().jwt(token -> token.subject("operator-1").claim("tenant_id", tenant.toString()))
                                .authorities(() -> "SCOPE_operator:write")))
                .andExpect(status().isBadRequest());
    }
}
