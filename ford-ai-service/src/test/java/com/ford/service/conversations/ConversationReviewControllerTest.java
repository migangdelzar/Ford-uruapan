package com.ford.service.conversations;

import com.ford.service.config.SecurityConfiguration;
import com.ford.service.conversations.adapter.in.web.ConversationReviewController;
import com.ford.service.conversations.application.ConversationReviewService;
import com.ford.service.ai.application.DecisionTraceEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
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

@WebMvcTest(ConversationReviewController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class ConversationReviewControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired MockMvc mockMvc;
    @MockitoBean ConversationReviewService reviewService;

    @Test
    void listsMinimalReviewMetadataForAuthenticatedTenant() throws Exception {
        var reviewId = UUID.randomUUID();
        when(reviewService.list(eq(TENANT), eq("PENDING"), eq(25)))
                .thenReturn(List.of(new ConversationReviewService.ReviewItem(reviewId, TENANT,
                        UUID.randomUUID(), UUID.randomUUID(), "assistant_response_review", "PENDING",
                        Instant.parse("2026-10-20T12:00:00Z"), Instant.parse("2026-10-20T12:00:00Z"))));

        mockMvc.perform(get("/api/v1/conversation-reviews")
                        .param("status", "PENDING")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                        .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(reviewId.toString()))
                .andExpect(jsonPath("$.items[0].reasonCode").value("assistant_response_review"))
                .andExpect(jsonPath("$.items[0].content").doesNotExist());
    }

    @Test
    void resolvesReviewAsAuthenticatedOperator() throws Exception {
        var reviewId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/conversation-reviews/{id}/resolve", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                        .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:write")))
                .andExpect(status().isNoContent());

        verify(reviewService).resolve(eq(TENANT), eq(reviewId), any());
    }

    @Test
    void rejectsCrossTenantQueueRequest() throws Exception {
        mockMvc.perform(get("/api/v1/conversation-reviews")
                        .param("tenantId", OTHER_TENANT.toString())
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                        .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reviewService);
    }

    @Test
    void refusesUnscopedQueueForOperatorWithMultipleTenants() throws Exception {
        mockMvc.perform(get("/api/v1/conversation-reviews")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                        .claim("tenant_ids", List.of(TENANT.toString(), OTHER_TENANT.toString())))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reviewService);
    }

    @Test
    void returnsOnlyBoundedTraceMetadataForAuthorizedTenant() throws Exception {
        var conversation = UUID.randomUUID();
        var receipt = UUID.randomUUID();
        when(reviewService.trace(eq(TENANT), eq(conversation), eq(receipt))).thenReturn(List.of(
                new DecisionTraceEvent("route", "decision_route_selected", "VEHICLE_DETAILS", "none",
                        null, "completed", 3, Instant.parse("2026-10-21T12:00:00Z"))));

        mockMvc.perform(get("/api/v1/conversation-reviews/{conversation}/turns/{receipt}/trace", conversation, receipt)
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                        .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.events[0].stage").value("route"))
                .andExpect(jsonPath("$.events[0].reasonCode").value("decision_route_selected"))
                .andExpect(jsonPath("$.events[0].content").doesNotExist())
                .andExpect(jsonPath("$.events[0].toolArguments").doesNotExist());
    }

    @Test
    void rejectsCrossTenantTraceQueryBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/v1/conversation-reviews/{conversation}/turns/{receipt}/trace",
                                UUID.randomUUID(), UUID.randomUUID())
                        .param("tenantId", OTHER_TENANT.toString())
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                        .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reviewService);
    }

    @Test
    void requiresTenantSelectionForMultiTenantTraceQuery() throws Exception {
        mockMvc.perform(get("/api/v1/conversation-reviews/{conversation}/turns/{receipt}/trace",
                                UUID.randomUUID(), UUID.randomUUID())
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                        .claim("tenant_ids", List.of(TENANT.toString(), OTHER_TENANT.toString())))
                                .authorities(() -> "SCOPE_operator:read")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reviewService);
    }
}
