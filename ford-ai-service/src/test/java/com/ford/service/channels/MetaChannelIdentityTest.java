package com.ford.service.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.in.meta.WhatsAppChannelAdapter;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.RawWebhook;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MetaChannelIdentityTest {

    @Test
    void separatesSessionsWhenTheSameParticipantUsesDifferentPhoneNumbers() {
        var adapter = new WhatsAppChannelAdapter(new ObjectMapper());
        var first = adapter.parseInbound(new RawWebhook(UUID.randomUUID(), """
                {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"phone-1"},"messages":[{"id":"wamid.1","from":"customer-1","text":{"body":"Hola"}}]}}]}]}
                """, "ref-1"));
        var second = adapter.parseInbound(new RawWebhook(UUID.randomUUID(), """
                {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"phone-2"},"messages":[{"id":"wamid.2","from":"customer-1","text":{"body":"Hola"}}]}}]}]}
                """, "ref-2"));

        assertThat(first.providerAccountId()).isEqualTo("phone-1");
        assertThat(second.providerAccountId()).isEqualTo("phone-2");
        assertThat(first.conversationId()).isNotEqualTo(second.conversationId());
    }
}
