package com.ford.service.channels;

import com.ford.service.channels.adapter.in.meta.WhatsAppChannelAdapter;
import com.ford.service.channels.application.ChannelMessageService;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.channels.domain.InboundMessageAccepted;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelMessageEventTest {

    @Test
    void publishesAcceptedMessageOnceAfterFirstDelivery() {
        var published = new ArrayList<Object>();
        ApplicationEventPublisher publisher = published::add;
        var service = new ChannelMessageService(publisher);
        var adapter = new WhatsAppChannelAdapter(new ObjectMapper());
        var webhook = new RawWebhook(UUID.randomUUID(), """
                {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"phone-42"},"messages":[{"id":"wamid.event-1","from":"5214521234567","text":{"body":"Hola"}}]}}]}]}
                """, "ref-1");

        assertThat(service.accept(webhook, adapter)).isNotNull();
        assertThat(service.accept(webhook, adapter)).isNull();
        assertThat(published).hasSize(1);
        assertThat(published.getFirst()).isInstanceOf(InboundMessageAccepted.class);
        assertThat(((InboundMessageAccepted) published.getFirst()).message().text())
                .isEqualTo("Hola");
    }
}
