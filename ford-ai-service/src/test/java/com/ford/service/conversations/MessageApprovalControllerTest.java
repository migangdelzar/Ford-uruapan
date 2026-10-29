package com.ford.service.conversations;

import com.ford.service.config.SecurityConfiguration;
import com.ford.service.conversations.adapter.in.web.MessageApprovalController;
import com.ford.service.conversations.api.MessageApprovalActionResult;
import com.ford.service.conversations.api.MessageApprovalView;
import com.ford.service.conversations.application.MessageApprovalService;
import com.ford.service.conversations.domain.MessageApproval.Action;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MessageApprovalController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class MessageApprovalControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONVERSATION = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID APPROVAL = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired MockMvc mockMvc;
    @MockitoBean MessageApprovalService approvals;

    @Test
    void listsDraftOnlyForAuthorizedTenantAndReadOperator() throws Exception {
        when(approvals.list(any(), eq(TENANT), eq(CONVERSATION))).thenReturn(List.of(
                new MessageApprovalView(APPROVAL, TENANT, CONVERSATION, 0, 9,
                        "MISSING_EVIDENCE", "REVIEW", "Draft", Set.of(Action.EDIT))));

        mockMvc.perform(get("/api/v1/conversations/{id}/message-approvals", CONVERSATION)
                        .param("tenantId", TENANT.toString())
                        .with(jwt().jwt(token -> token.subject("advisor-1").claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(APPROVAL.toString()))
                .andExpect(jsonPath("$.items[0].draftText").value("Draft"));
        verify(approvals).list(any(), eq(TENANT), eq(CONVERSATION));
    }

    @Test
    void postsExactActionWithConversationEtagAndIdempotencyKey() throws Exception {
        when(approvals.act(any(), eq(TENANT), eq(CONVERSATION), eq(APPROVAL), eq(9L), eq("approve-1"), any()))
                .thenReturn(new MessageApprovalActionResult(APPROVAL, "QUEUED", 10, UUID.randomUUID()));

        mockMvc.perform(post("/api/v1/conversations/{conversation}/message-approvals/{approval}/actions",
                        CONVERSATION, APPROVAL)
                        .param("tenantId", TENANT.toString())
                        .header("If-Match", "\"v9\"")
                        .header("Idempotency-Key", "approve-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"expectedApprovalVersion\":0,\"exactText\":\"Draft\"}")
                        .with(jwt().jwt(token -> token.subject("advisor-1").claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.conversationVersion").value(10));
        verify(approvals).act(any(), eq(TENANT), eq(CONVERSATION), eq(APPROVAL), eq(9L), eq("approve-1"), any());
    }

    @Test
    void refusesCrossTenantBeforeReadingApproval() throws Exception {
        mockMvc.perform(get("/api/v1/conversations/{id}/message-approvals", CONVERSATION)
                        .param("tenantId", OTHER_TENANT.toString())
                        .with(jwt().jwt(token -> token.subject("advisor-1").claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(approvals);
    }

    @Test
    void rejectsReadOnlyAdvisorActionAtControllerBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{conversation}/message-approvals/{approval}/actions",
                        CONVERSATION, APPROVAL)
                        .param("tenantId", TENANT.toString())
                        .header("If-Match", "\"v9\"")
                        .header("Idempotency-Key", "approve-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"expectedApprovalVersion\":0,\"exactText\":null}")
                        .with(jwt().jwt(token -> token.subject("advisor-1").claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(approvals);
    }
}
