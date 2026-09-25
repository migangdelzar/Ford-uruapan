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
}
