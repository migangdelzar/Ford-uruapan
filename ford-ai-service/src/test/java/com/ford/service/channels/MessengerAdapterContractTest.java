package com.ford.service.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.in.meta.MessengerChannelAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.RawWebhook;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MessengerAdapterContractTest {
    @Test
    void normalizesTextMessage() {
        var message = new MessengerChannelAdapter(new ObjectMapper()).parseInbound(new RawWebhook(
                UUID.randomUUID(), """
                        {"entry":[{"messaging":[{"sender":{"id":"psid-1"},"message":{"mid":"mid.1","text":"Precio de Bronco"}}]}]}
                        """, "{}"));
        assertThat(message).extracting(InboundMessage::channel, InboundMessage::providerMessageId, InboundMessage::text)
                .containsExactly(Channel.MESSENGER, "mid.1", "Precio de Bronco");
    }
}
