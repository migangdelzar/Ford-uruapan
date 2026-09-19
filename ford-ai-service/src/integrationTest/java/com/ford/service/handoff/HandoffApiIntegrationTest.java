package com.ford.service.handoff;

import com.ford.service.handoff.application.HandoffService;
import com.ford.service.handoff.adapter.in.web.HandoffController;
import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.config.SecurityConfiguration;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HandoffController.class)
@Import(SecurityConfiguration.class)
class HandoffApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    HandoffService service;

    @Test
    void createsOperatorInboxTicket() throws Exception {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var ticket = new HandoffTicket(
                UUID.randomUUID(), tenantId, conversationId, null, HandoffStatus.HUMAN_PENDING,
                null, "HOT_LEAD", 0, Instant.parse("2026-09-18T18:00:00Z"), null, null);
        var principal = new OperatorPrincipal(UUID.randomUUID(), Set.of(tenantId), Set.of("ROLE_OPERATOR"));
        when(service.request(any(), any())).thenReturn(ticket);

        mockMvc.perform(post("/api/operator/handoffs")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                                new UsernamePasswordAuthenticationToken(principal, "n", java.util.List.of(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR")))))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","conversationId":"%s","reason":"HOT_LEAD"}
                                """.formatted(tenantId, conversationId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HUMAN_PENDING"))
                .andExpect(jsonPath("$.reason").value("HOT_LEAD"));
    }

    @Test
    void ignoresCallerSuppliedOperatorIdentityWhenClaiming() throws Exception {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var ticketId = UUID.randomUUID();
        var principal = new OperatorPrincipal(UUID.randomUUID(), Set.of(tenantId), Set.of("ROLE_OPERATOR"));
        var ticket = new HandoffTicket(ticketId, tenantId, conversationId, null, HandoffStatus.HUMAN_ACTIVE,
                principal.operatorId(), "HOT_LEAD", 1, Instant.now(), Instant.now(), null);
        when(service.claim(eq(ticketId), eq(0L), eq(principal))).thenReturn(ticket);

        mockMvc.perform(post("/api/operator/handoffs/{ticketId}/claim", ticketId)
                        .param("expectedVersion", "0")
                        .param("operatorId", UUID.randomUUID().toString())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                                new UsernamePasswordAuthenticationToken(principal, "n", java.util.List.of(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR"))))))
                .andExpect(status().isOk());

        verify(service).claim(ticketId, 0L, principal);
    }
}
