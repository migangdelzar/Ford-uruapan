package com.ford.service.channels;

import com.ford.service.channels.adapter.in.meta.MetaWebhookController;
import com.ford.service.channels.adapter.in.meta.MetaWebhookProperties;
import com.ford.service.channels.application.InboundWebhookService;
import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.domain.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetaWebhookController.class)
class MetaWebhookIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ChannelAdapter adapter;

    @MockitoBean
    InboundWebhookService inboundWebhookService;

    @MockitoBean
    MetaWebhookProperties properties;

    @BeforeEach
    void setUp() {
        when(adapter.channel()).thenReturn(Channel.WHATSAPP);
        when(properties.getVerifyToken()).thenReturn("verify-token");
        doNothing().when(inboundWebhookService)
                .accept(any(), any(), any(), any(), any());
    }

    @Test
    void verifiesMetaSubscriptionAndAcceptsSignedWebhook() throws Exception {
        mockMvc.perform(get("/webhooks/meta/whatsapp")
                        .queryParam("hub.mode", "subscribe")
                        .queryParam("hub.verify_token", "verify-token")
                        .queryParam("hub.challenge", "challenge-123"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/webhooks/meta/whatsapp/opaque-key")
                        .header("X-Hub-Signature-256", "sha256=valid")
                        .contentType(APPLICATION_JSON)
                        .content("{\"entry\":[]}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        doThrow(new SecurityException("invalid")).when(inboundWebhookService)
                .accept(any(), any(), any(), any(), any());

        mockMvc.perform(post("/webhooks/meta/whatsapp/opaque-key")
                        .header("X-Hub-Signature-256", "sha256=invalid")
                        .contentType(APPLICATION_JSON)
                        .content("{\"entry\":[]}"))
                .andExpect(status().isForbidden());
    }
}
