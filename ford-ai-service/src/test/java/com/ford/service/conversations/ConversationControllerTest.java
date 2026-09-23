package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.in.web.ConversationController;
import com.ford.service.conversations.api.ConversationPage;
import com.ford.service.conversations.api.PageInfoView;
import com.ford.service.conversations.application.port.in.ConversationQuery;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConversationController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
class ConversationControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ConversationQuery query;

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
}
