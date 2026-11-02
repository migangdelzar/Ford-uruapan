package com.ford.service.ai;

import com.ford.service.ai.adapter.in.web.AiResponseController;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.application.AiTestSessionStore;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.api.AssistantTurn;
import com.ford.service.config.SecurityConfiguration;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.Intent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiResponseController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class AiResponseControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AssistantService assistant;

    @MockitoBean
    AiTestSessionStore sessions;

    @Test
    void respondsUsingTheAuthenticatedTenantAndLatestUserMessage() throws Exception {
        var conversationId = UUID.randomUUID();
        when(sessions.recentMessages(TENANT, "operator-1", conversationId, 40)).thenReturn(List.of());
        when(assistant.respond(any(), anyString())).thenReturn(new AssistantTurn(
                "Tenemos Explorer disponible.", Intent.INVENTORY, false, List.of(),
                "openai", "configured-model", ConversationDecision.deterministicDefault()));

        mockMvc.perform(post("/api/v1/ai/respond")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:write"))
                        .contentType("application/json")
                        .content("""
                                {"conversationId":"%s","messages":[
                                  {"role":"user","content":"Explorer disponible?"}
                                ]}
                                """.formatted(conversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.replyText").value("Tenemos Explorer disponible."))
                .andExpect(jsonPath("$.handoffSuggested").value(false))
                .andExpect(jsonPath("$.intent").value("INVENTORY"))
                .andExpect(jsonPath("$.latencyMs").doesNotExist())
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.model").doesNotExist())
                .andExpect(jsonPath("$.decision").doesNotExist())
                .andExpect(jsonPath("$.systemPromptSent").doesNotExist())
                .andExpect(jsonPath("$.rawResponse").doesNotExist());

        verify(assistant).respond(any(), eq("Explorer disponible?"));
        verify(sessions).saveTurn(eq(TENANT), eq("operator-1"), eq(conversationId),
                eq("Explorer disponible?"), eq("Tenemos Explorer disponible."));
    }

    @Test
    void rejectsInferenceWithoutMessages() throws Exception {
        mockMvc.perform(post("/api/v1/ai/respond")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:write"))
                        .contentType("application/json")
                        .content("{\"messages\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresInferenceAuthority() throws Exception {
        mockMvc.perform(post("/api/v1/ai/respond")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", TENANT.toString())))
                        .contentType("application/json")
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"Hola\"}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reusesStoredRecentTurnsForTheSameConversation() throws Exception {
        var conversationId = UUID.randomUUID();
        when(sessions.recentMessages(TENANT, "operator-1", conversationId, 40)).thenReturn(List.of(
                new AiTestSessionStore.StoredMessage("user", "Busco una SUV familiar"),
                new AiTestSessionStore.StoredMessage("assistant", "¿Cuántas plazas necesitas?")));
        when(assistant.respond(any(), anyString())).thenReturn(new AssistantTurn(
                "La Explorer tiene tres filas.", Intent.CATALOG, false, List.of(),
                "deepseek", "deepseek-chat", ConversationDecision.deterministicDefault()));

        mockMvc.perform(post("/api/v1/ai/respond")
                        .with(jwt().jwt(token -> token.subject("operator-1")
                                .claim("tenant_id", TENANT.toString()))
                                .authorities(() -> "SCOPE_operator:write"))
                        .contentType("application/json")
                        .content("""
                                {"conversationId":"%s","messages":[{"role":"user","content":"Somos cinco"}]}
                                """.formatted(conversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()));

        verify(assistant).respond(argThat(context -> context.memoryContext().contains("Busco una SUV familiar")
                && context.memoryContext().contains("¿Cuántas plazas necesitas?")), eq("Somos cinco"));
        verify(sessions).saveTurn(TENANT, "operator-1", conversationId,
                "Somos cinco", "La Explorer tiene tres filas.");
    }
}
