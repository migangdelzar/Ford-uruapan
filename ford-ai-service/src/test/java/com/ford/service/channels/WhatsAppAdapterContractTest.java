package com.ford.service.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.in.meta.WhatsAppChannelAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.RawWebhook;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppAdapterContractTest {
    @Test
    void normalizesTextMessage() {
        var message = new WhatsAppChannelAdapter(new ObjectMapper()).parseInbound(new RawWebhook(
                UUID.randomUUID(), """
                        {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"phone-42"},"messages":[{"id":"wamid.1","from":"5214521234567","text":{"body":"¿Hay Ranger?"}}]}}]}]}
                        """, "{}"));
        assertThat(message).extracting(InboundMessage::channel, InboundMessage::providerMessageId, InboundMessage::text)
                .containsExactly(Channel.WHATSAPP, "wamid.1", "¿Hay Ranger?");
    }
}
