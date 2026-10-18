package com.ford.service.conversations;

import com.ford.service.conversations.adapter.in.web.ShadowDraftController;
import com.ford.service.conversations.application.ShadowDraftService;
import com.ford.service.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShadowDraftController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class ShadowDraftControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ShadowDraftService shadowService;

    @Test
    void listsShadowDraftsForAuthorizedOperator() throws Exception {
        var conversationId = UUID.randomUUID();
        when(shadowService.list(any(), eq(TENANT), eq(conversationId))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/conversations/{id}/shadow-drafts", conversationId)
                        .param("tenantId", TENANT.toString())
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                        .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void rejectsOperatorWithoutReadAuthority() throws Exception {
        mockMvc.perform(get("/api/v1/conversations/{id}/shadow-drafts", UUID.randomUUID())
                        .param("tenantId", TENANT.toString())
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", TENANT.toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void recordsReviewForAuthorizedOperator() throws Exception {
        var conversationId = UUID.randomUUID();
        var draftId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/conversations/{id}/shadow-drafts/{draftId}/review", conversationId, draftId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPTED\",\"note\":\"good\"}")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                        .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:write")))
                .andExpect(status().isNoContent());

        verify(shadowService).review(any(), eq(TENANT), eq(conversationId), eq(draftId), eq("ACCEPTED"), eq("good"));
    }

    @Test
    void rejectsReviewWithoutWriteAuthority() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{id}/shadow-drafts/{draftId}/review",
                        UUID.randomUUID(), UUID.randomUUID())
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPTED\"}")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                        .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isForbidden());
    }
}
