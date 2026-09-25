package com.ford.service.security;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.channels.security.MetaSignatureVerifier;
import com.ford.service.config.SecurityConfiguration;
import com.ford.service.handoff.adapter.in.web.OperatorConversationHandoffController;
import com.ford.service.handoff.application.HandoffService;
import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import com.ford.service.handoff.security.TenantAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OperatorConversationHandoffController.class)
@Import({SecurityConfiguration.class, TenantAuthorization.class})
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
        "ford.security.jwt.enabled=true",
        "ford.security.jwt.issuer-uri=https://issuer.example.test",
        "ford.security.jwt.audience=ford-ai-service"
})
class ToolAndWebhookSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    HandoffService handoffService;

    @MockitoBean
    JwtDecoder jwtDecoder;

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
        mockMvc.perform(post("/api/v1/handoffs")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectsTheModulithTopologyFromAnonymousRequests() throws Exception {
        mockMvc.perform(get("/actuator/modulith"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void keepsHealthInfoAndMetaWebhooksPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/webhooks/meta/events"))
                .andExpect(status().isNotFound());
    }

    @Test
    void permitsOperatorScope() throws Exception {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        when(handoffService.request(any(), any())).thenReturn(new HandoffTicket(UUID.randomUUID(), tenantId, conversationId,
                null, HandoffStatus.HUMAN_PENDING, null, "HOT_LEAD", 0, Instant.now(), null, null));
        var principal = new OperatorPrincipal(UUID.randomUUID(), Set.of(tenantId), Set.of("ROLE_OPERATOR"));

        mockMvc.perform(post("/api/v1/handoffs")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(token -> token
                                        .subject(principal.operatorId().toString())
                                        .claim("tenant_ids", Set.of(tenantId.toString()))
                                        .claim("scope", "operator:read handoff:manage"))
                                .authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR"),
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_operator:read"),
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_handoff:manage"),
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("TENANT_" + tenantId)))
                        .contentType(APPLICATION_JSON)
                        .content("{\"tenantId\":\"%s\",\"conversationId\":\"%s\",\"reason\":\"HOT_LEAD\"}"
                                .formatted(tenantId, conversationId)))
                .andExpect(status().isCreated());
    }

    @Test
    void permitsTheHandoffScopeWithoutRequiringAClientRoleHeader() throws Exception {
        var tenantId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/handoffs")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(token -> token
                                        .subject(UUID.randomUUID().toString())
                                        .claim("tenant_ids", Set.of(tenantId.toString()))
                                        .claim("scope", "handoff:manage"))
                                .authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_handoff:manage")))
                        .contentType(APPLICATION_JSON)
                        .content("{\"tenantId\":\"%s\",\"conversationId\":\"%s\",\"reason\":\"test\"}"
                                .formatted(tenantId, UUID.randomUUID())))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsHandoffForATenantOutsideJwtAttributes() throws Exception {
        var tokenTenantId = UUID.randomUUID();
        var requestedTenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/handoffs")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(token -> token
                                        .subject(UUID.randomUUID().toString())
                                        .claim("tenant_ids", Set.of(tokenTenantId.toString()))
                                        .claim("scope", "handoff:manage")
                                        .claim("roles", Set.of("OPERATOR")))
                                .authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR"),
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_handoff:manage")))
                        .contentType(APPLICATION_JSON)
                        .content("{\"tenantId\":\"%s\",\"conversationId\":\"%s\",\"reason\":\"HOT_LEAD\"}"
                                .formatted(requestedTenantId, conversationId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsAuthenticatedRequestWithoutHandoffScope() throws Exception {
        mockMvc.perform(post("/api/v1/handoffs")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(token -> token
                                        .subject(UUID.randomUUID().toString())
                                        .claim("scope", "operator:read")))
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void doesNotExposeTheUnversionedOperatorRoute() throws Exception {
        mockMvc.perform(post("/api/operator/handoffs")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(token -> token
                                        .subject(UUID.randomUUID().toString())
                                        .claim("tenant_ids", Set.of(UUID.randomUUID().toString()))
                                        .claim("scope", "handoff:manage"))
                                .authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR"),
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_handoff:manage")))
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
