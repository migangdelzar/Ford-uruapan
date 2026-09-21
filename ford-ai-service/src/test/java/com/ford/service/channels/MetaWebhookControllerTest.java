package com.ford.service.channels;

import com.ford.service.channels.adapter.in.meta.MetaWebhookController;
import com.ford.service.channels.adapter.in.meta.MetaWebhookProperties;
import com.ford.service.channels.application.InboundWebhookService;
import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.config.SecurityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetaWebhookController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class MetaWebhookControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ChannelAdapter channelAdapter;

    @MockitoBean
    InboundWebhookService inboundWebhookService;

    @MockitoBean
    MetaWebhookProperties properties;

    @BeforeEach
    void configure() {
        when(channelAdapter.channel()).thenReturn(Channel.WHATSAPP);
        when(properties.getVerifyToken()).thenReturn("verification-token");
    }

    @Test
    void verifiesUsingTheSameChannelPathUsedForInboundEvents() throws Exception {
        mockMvc.perform(get("/webhooks/meta/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "verification-token")
                        .param("hub.challenge", "challenge-123"))
                .andExpect(status().isOk())
                .andExpect(content().string("challenge-123"));
    }

    @Test
    void rejectsTheLegacyWebhookKeyPath() throws Exception {
        mockMvc.perform(get("/webhooks/meta/whatsapp/opaque-webhook-key")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "verification-token")
                        .param("hub.challenge", "challenge-123"))
                .andExpect(status().isNotFound());
    }

    @Test
    void logsTheSafeReasonWhenMetaRejectsAWebhook(CapturedOutput output) throws Exception {
        doThrow(new SecurityException("Invalid Meta signature")).when(inboundWebhookService)
                .accept(any(), any(), any(), any(), any());

        mockMvc.perform(post("/webhooks/meta/whatsapp")
                        .contentType(APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=redacted")
                        .content("{\"sensitive\":\"do-not-log\"}"))
                .andExpect(status().isForbidden());

        org.assertj.core.api.Assertions.assertThat(output.getOut())
                .contains("Meta webhook rejected")
                .contains("channel=whatsapp")
                .contains("reason=Invalid Meta signature")
                .doesNotContain("sha256=redacted")
                .doesNotContain("do-not-log");
    }
}
