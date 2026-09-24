package com.ford.service.security;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.channels.security.MetaSignatureVerifier;
import com.ford.service.config.SecurityConfiguration;
import com.ford.service.handoff.adapter.in.web.HandoffController;
import com.ford.service.handoff.application.HandoffService;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HandoffController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class ToolAndWebhookSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    HandoffService handoffService;

    @Test
    void rejectsMissingTenantForToolExecution() {
        assertThatThrownBy(() -> new ToolAuthorizationPolicy().readOnlyTools(new AiExecutionContext(null, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTamperedWebhookSignature() {
        var verifier = new MetaSignatureVerifier();
        assertThatThrownBy(() -> verifier.verify("body", "sha256=wrong", "secret"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsAnonymousOperatorRequest() throws Exception {
        mockMvc.perform(post("/api/operator/handoffs")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnonymousRealtimeStreamRequest() throws Exception {
        mockMvc.perform(get("/api/v1/operator/events/stream")
                        .queryParam("tenantId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void permitsOperatorRole() throws Exception {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        when(handoffService.request(any())).thenReturn(new HandoffTicket(UUID.randomUUID(), tenantId, conversationId,
                null, HandoffStatus.HUMAN_PENDING, null, "HOT_LEAD", 0, Instant.now(), null, null));

        mockMvc.perform(post("/api/operator/handoffs")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("operator").roles("OPERATOR"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"tenantId\":\"%s\",\"conversationId\":\"%s\",\"reason\":\"HOT_LEAD\"}"
                                .formatted(tenantId, conversationId)))
                .andExpect(status().isCreated());
    }
}
