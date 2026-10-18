package com.ford.service.handoff;

import com.ford.service.handoff.application.HandoffService;
import com.ford.service.handoff.application.AfterHoursDigestService;
import com.ford.service.handoff.api.AfterHoursDigest;
import com.ford.service.handoff.adapter.in.web.OperatorConversationHandoffController;
import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.config.SecurityConfiguration;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import com.ford.service.handoff.security.TenantAuthorization;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OperatorConversationHandoffController.class)
@Import({SecurityConfiguration.class, TenantAuthorization.class})
class HandoffApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    HandoffService service;

    @MockitoBean
    AfterHoursDigestService digestService;

    @Test
    void createsOperatorInboxTicket() throws Exception {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var ticket = new HandoffTicket(
                UUID.randomUUID(), tenantId, conversationId, null, HandoffStatus.HUMAN_PENDING,
                null, "HOT_LEAD", 0, Instant.parse("2026-09-18T18:00:00Z"), null, null);
        var principal = new OperatorPrincipal(UUID.randomUUID(), Set.of(tenantId), Set.of("ROLE_OPERATOR"));
        when(service.request(any(), any())).thenReturn(ticket);

        mockMvc.perform(post("/api/v1/handoffs")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                                new UsernamePasswordAuthenticationToken(principal, "n", java.util.List.of(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR"),
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_handoff:manage")))))
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

        mockMvc.perform(post("/api/v1/handoffs/{ticketId}/claim", ticketId)
                        .param("expectedVersion", "0")
                        .param("operatorId", UUID.randomUUID().toString())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                                new UsernamePasswordAuthenticationToken(principal, "n", java.util.List.of(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR"),
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_handoff:manage"))))))
                .andExpect(status().isOk());

        verify(service).claim(ticketId, 0L, principal);
    }

    @Test
    void servesTenantScopedAfterHoursDigestToAuthenticatedOperator() throws Exception {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var handoffId = UUID.randomUUID();
        var principal = new OperatorPrincipal(UUID.randomUUID(), Set.of(tenantId), Set.of("SCOPE_operator:read"));
        var digest = new AfterHoursDigest(tenantId, java.time.LocalDate.parse("2026-10-19"),
                "America/Mexico_City", true, Instant.parse("2026-10-19T14:45:00Z"),
                Instant.parse("2026-10-17T20:00:00Z"), Instant.parse("2026-10-19T14:50:00Z"),
                1, 0, 1, java.util.List.of(new AfterHoursDigest.Item(handoffId, conversationId,
                        Instant.parse("2026-10-19T14:46:00Z"), HandoffStatus.HUMAN_PENDING,
                        "CUSTOMER_REQUEST", 2, true)));
        when(digestService.get(tenantId, principal)).thenReturn(digest);

        mockMvc.perform(get("/api/v1/handoffs/after-hours-digest")
                        .param("tenantId", tenantId.toString())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                                new UsernamePasswordAuthenticationToken(principal, "n", java.util.List.of(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_operator:read"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-10-19"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.partialPacketCount").value(1))
                .andExpect(jsonPath("$.items[0].handoffId").value(handoffId.toString()))
                .andExpect(jsonPath("$.items[0].packetPartial").value(true))
                .andExpect(jsonPath("$.items[0].customerName").doesNotExist());
        verify(digestService).get(tenantId, principal);
    }
}
