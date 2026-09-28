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
                        {"entry":[{"id":"page-1","messaging":[{"sender":{"id":"psid-1"},"message":{"mid":"mid.1","text":"Precio de Bronco"}}]}]}
                        """, "{}"));
        assertThat(message).extracting(InboundMessage::channel, InboundMessage::providerMessageId, InboundMessage::text)
                .containsExactly(Channel.MESSENGER, "mid.1", "Precio de Bronco");
    }

    @Test
    void extractsOnlyCustomerMessagesAndSupportsMultipleAccountsAndEvents() {
        var adapter = new MessengerChannelAdapter(new ObjectMapper());
        var body = """
                {"entry":[
                  {"id":"page-1","messaging":[
                    {"sender":{"id":"customer-1"},"message":{"mid":"m1","text":"Hola"}},
                    {"sender":{"id":"page-1"},"message":{"mid":"echo","text":"eco","is_echo":true}},
                    {"sender":{"id":"customer-1"},"delivery":{"mids":["m1"]}},
                    {"sender":{"id":"customer-1"},"timestamp":1780000000000,"message":{"mid":"m3","text":"Mire esto","attachments":[{"type":"image","payload":{"url":"https://private.example/image"}}]}}]},
                  {"id":"page-2","messaging":[
                    {"sender":{"id":"customer-2"},"message":{"mid":"m2","quick_reply":{"payload":"opaque"}}}]}
                ]}
                """;
        var accounts = adapter.providerAccountIds(body);

        assertThat(accounts).containsExactly("page-1", "page-2");
        var messages = adapter.parseInboundMessages(new RawWebhook(UUID.randomUUID(), body, "{}"), "page-1");
        assertThat(messages).extracting(InboundMessage::providerMessageId).containsExactly("m1", "m3");
        var mediaMessage = adapter.parseInboundMessages(new RawWebhook(UUID.randomUUID(), body, "{}"), "page-1").get(1);
        assertThat(mediaMessage.kind()).isEqualTo(com.ford.service.channels.domain.InboundMessageKind.UNSUPPORTED);
        assertThat(mediaMessage.text()).contains("Mire esto", "multimedia").doesNotContain("private.example");
        assertThat(mediaMessage.receivedAt()).isEqualTo(java.time.Instant.ofEpochMilli(1780000000000L));
        var quickReply = adapter.parseInboundMessages(new RawWebhook(UUID.randomUUID(), body, "{}"), "page-2").getFirst();
        assertThat(quickReply.kind()).isEqualTo(com.ford.service.channels.domain.InboundMessageKind.UNSUPPORTED);
        assertThat(quickReply.text()).doesNotContain("opaque");
    }
}
