package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.in.web.ConversationController;
import com.ford.service.conversations.api.ConversationPage;
import com.ford.service.conversations.api.PageInfoView;
import com.ford.service.conversations.application.port.in.ConversationQuery;
import com.ford.service.conversations.application.ConversationCommandService;
import com.ford.service.conversations.application.ConversationMemoryRestartService;
import com.ford.service.conversations.application.CustomerMemoryForgetService;
import com.ford.service.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.anyString;

@WebMvcTest(ConversationController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class ConversationControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ConversationQuery query;

    @MockitoBean
    ConversationCommandService commands;

    @MockitoBean
    ConversationMemoryRestartService memoryRestarts;

    @MockitoBean
    CustomerMemoryForgetService memoryForget;

    @Test
    void listsConversationsForJwtTenant() throws Exception {
        when(query.search(any())).thenReturn(new ConversationPage(List.of(), new PageInfoView(0, null, false)));

        mockMvc.perform(get("/api/v1/conversations")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsOperatorWithoutReadAuthority() throws Exception {
        mockMvc.perform(get("/api/v1/conversations")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", TENANT.toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnsEtagForConversationVersion() throws Exception {
        var conversationId = UUID.randomUUID();
        var view = new com.ford.service.conversations.api.ConversationView(
                conversationId, TENANT, Channel.WHATSAPP,
                com.ford.service.conversations.domain.ConversationState.BOT_ACTIVE,
                7, new com.ford.service.conversations.api.ParticipantView("521", null, null),
                null, null, null, java.time.Instant.parse("2026-09-23T12:00:00Z"));
        when(query.find(TENANT, conversationId)).thenReturn(java.util.Optional.of(view));

        mockMvc.perform(get("/api/v1/conversations/{id}", conversationId)
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v7\""));
    }

    @Test
    void restartAcceptsAuditedReasonFromAuthorizedOperator() throws Exception {
        var conversationId = UUID.randomUUID();
        when(memoryRestarts.restart(any(), eq(TENANT), eq(conversationId), eq(7L), anyString(),
                eq(com.ford.service.conversations.api.ConversationMemoryRestartRequest.RestartReason.CUSTOMER_REQUEST)))
                .thenReturn(new com.ford.service.conversations.api.ConversationMemoryRestartReceipt(
                        conversationId, 8, java.time.Instant.parse("2026-10-20T12:00:00Z")));

        mockMvc.perform(post("/api/v1/conversations/{id}/restart", conversationId)
                        .header("Idempotency-Key", "restart-api-1")
                        .header("If-Match", "v7")
                        .contentType("application/json")
                        .content("{\"reason\":\"CUSTOMER_REQUEST\"}")
                        .with(jwt().jwt(token -> token.subject("operator-1").claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:write")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v8\""));

        verify(memoryRestarts).restart(any(), eq(TENANT), eq(conversationId), eq(7L), eq("restart-api-1"),
                eq(com.ford.service.conversations.api.ConversationMemoryRestartRequest.RestartReason.CUSTOMER_REQUEST));
    }

    @Test
    void forgetFactsRouteRequiresWriteAndReturnsCountOnlyReceipt() throws Exception {
        var conversationId = UUID.randomUUID();
        when(memoryForget.forget(any(), eq(TENANT), eq(conversationId), eq("forget-api-1")))
                .thenReturn(new com.ford.service.conversations.api.CustomerMemoryForgetReceipt(
                        conversationId, 2, java.time.Instant.parse("2026-10-20T12:00:00Z")));

        mockMvc.perform(delete("/api/v1/conversations/{id}/customer-memory", conversationId)
                        .header("Idempotency-Key", "forget-api-1")
                        .with(jwt().jwt(token -> token.subject("operator-1").claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedFactCount").value(2))
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()));

        verify(memoryForget).forget(any(), eq(TENANT), eq(conversationId), eq("forget-api-1"));
    }

    @Test
    void forgetFactsRouteRejectsReadOnlyOperatorBeforeServiceCall() throws Exception {
        mockMvc.perform(delete("/api/v1/conversations/{id}/customer-memory", UUID.randomUUID())
                        .header("Idempotency-Key", "forget-read-only")
                        .with(jwt().jwt(token -> token.subject("reader").claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verifyNoInteractions(memoryForget);
    }

    @Test
    void forgetFactsRouteAllowsTenantScopedAdmin() throws Exception {
        var conversationId = UUID.randomUUID();
        when(memoryForget.forget(any(), eq(TENANT), eq(conversationId), eq("admin-forget-api")))
                .thenReturn(new com.ford.service.conversations.api.CustomerMemoryForgetReceipt(
                        conversationId, 0, java.time.Instant.parse("2026-10-20T12:00:00Z")));

        mockMvc.perform(delete("/api/v1/conversations/{id}/customer-memory", conversationId)
                        .header("Idempotency-Key", "admin-forget-api")
                        .with(jwt().jwt(token -> token.subject("admin-1").claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedFactCount").value(0));

        verify(memoryForget).forget(any(), eq(TENANT), eq(conversationId), eq("admin-forget-api"));
    }
}
