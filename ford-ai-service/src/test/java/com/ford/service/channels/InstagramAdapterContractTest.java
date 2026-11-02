package com.ford.service.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.in.meta.InstagramChannelAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.RawWebhook;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InstagramAdapterContractTest {
    @Test
    void normalizesTextMessage() {
        var message = new InstagramChannelAdapter(new ObjectMapper()).parseInbound(new RawWebhook(
                UUID.randomUUID(), """
                        {"entry":[{"id":"ig-account-1","messaging":[{"sender":{"id":"ig-user-1"},"message":{"mid":"igmid.1","text":"Colores disponibles"}}]}]}
                        """, "{}"));
        assertThat(message).extracting(InboundMessage::channel, InboundMessage::providerMessageId, InboundMessage::text)
                .containsExactly(Channel.INSTAGRAM, "igmid.1", "Colores disponibles");
    }

    @Test
    void ignoresEchoAndReadEventsAndParsesEachInstagramAccountSeparately() {
        var adapter = new InstagramChannelAdapter(new ObjectMapper());
        var body = """
                {"entry":[
                  {"id":"ig-account-1","messaging":[
                    {"sender":{"id":"ig-user-1"},"message":{"mid":"igmid.1","text":"Hola"}},
                    {"sender":{"id":"ig-account-1"},"message":{"mid":"echo","text":"eco","is_echo":true}},
                    {"sender":{"id":"ig-user-1"},"read":{"mid":"igmid.1"}}]},
                  {"id":"ig-account-2","messaging":[
                    {"sender":{"id":"ig-user-2"},"message":{"mid":"igmid.2","text":"Buenas"}}]}
                ]}
                """;

        assertThat(adapter.providerAccountIds(body)).containsExactly("ig-account-1", "ig-account-2");
        var firstAccountMessages = adapter.parseInboundMessages(
                new RawWebhook(UUID.randomUUID(), body, "{}"), "ig-account-1");
        assertThat(firstAccountMessages).extracting(InboundMessage::providerMessageId)
                .containsExactly("igmid.1");
        assertThat(adapter.parseInboundMessages(new RawWebhook(UUID.randomUUID(), body, "{}"), "ig-account-2"))
                .extracting(InboundMessage::providerMessageId).containsExactly("igmid.2");
    }
}
