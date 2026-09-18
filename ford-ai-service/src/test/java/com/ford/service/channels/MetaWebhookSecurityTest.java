package com.ford.service.channels;

import com.ford.service.channels.adapter.in.meta.WhatsAppChannelAdapter;
import com.ford.service.channels.application.ChannelMessageService;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.channels.domain.WebhookRequest;
import com.ford.service.channels.security.MetaSignatureVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaWebhookSecurityTest {

    @Test
    void acceptsValidHmacAndRejectsTamperedBody() throws Exception {
        var body = "{\"ok\":true}";
        var secret = "app-secret";
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var signature = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        var verifier = new MetaSignatureVerifier();

        verifier.verify(body, signature, secret);
        assertThatThrownBy(() -> verifier.verify(body + "!", signature, secret))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void acknowledgesDuplicateProviderMessageOnlyOnce() {
        var body = """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"wamid.duplicate","from":"5214521234567","text":{"body":"Hola"}}]}}]}]}
                """;
        var service = new ChannelMessageService();
        var adapter = new WhatsAppChannelAdapter(new ObjectMapper());
        var webhook = new RawWebhook(UUID.randomUUID(), body, "ref-1");

        assertThat(service.accept(webhook, adapter)).isNotNull();
        assertThat(service.accept(webhook, adapter)).isNull();
    }
}
