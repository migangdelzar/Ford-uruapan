package com.ford.service.ai;

import com.ford.service.ai.adapter.in.web.BotConfigurationController;
import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.ai.configuration.ClassificationProperties;
import com.ford.service.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BotConfigurationController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "ford.conversations.human-handoff.enabled=false")
class BotConfigurationControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean AiProperties ai;
    @MockitoBean ClassificationProperties classification;

    @Test
    void requiresBotManagementAuthority() throws Exception {
        mockMvc.perform(get("/api/v1/bot/config").with(jwt().jwt(token -> token.subject("operator-1"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnsRedactedConfiguration() throws Exception {
        when(ai.cloudEnabled()).thenReturn(true);
        when(ai.localEnabled()).thenReturn(false);
        when(ai.cloudChatModelVersion()).thenReturn("configured-model");
        mockMvc.perform(get("/api/v1/bot/config").with(jwt().jwt(token -> token.subject("operator-1"))
                        .authorities(() -> "SCOPE_bot:manage")))
                .andExpect(status().isOk());
    }
}
