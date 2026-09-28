package com.ford.service.channels;

import com.ford.service.channels.adapter.in.meta.WhatsAppChannelAdapter;
import com.ford.service.channels.application.ChannelMessageService;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.channels.api.InboundMessageAccepted;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelMessageEventTest {

    @Test
    void acceptsEveryMessageInABatchedWebhookAndPublishesOneEventPerMessage() {
        var published = new ArrayList<Object>();
        var service = new ChannelMessageService((ApplicationEventPublisher) published::add);
        var adapter = new WhatsAppChannelAdapter(new ObjectMapper());
        var webhook = new RawWebhook(UUID.randomUUID(), """
                {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"phone-42"},"messages":[
                  {"id":"wamid.batch-1","from":"5214521234567","text":{"body":"Hola"}},
                  {"id":"wamid.batch-2","from":"5214521234567","text":{"body":"Busco una Bronco"}}
                ]}}]}]}
                """, "ref-batch");

        var accepted = service.acceptAll(webhook, adapter);

        assertThat(accepted).hasSize(2);
        assertThat(published).hasSize(2);
        assertThat(published).allMatch(InboundMessageAccepted.class::isInstance);
        assertThat(service.acceptAll(webhook, adapter)).isEmpty();
        assertThat(published).hasSize(2);
    }

    @Test
    void singularAcceptRejectsBatchedWebhookInsteadOfDroppingMessages() {
        var service = new ChannelMessageService((ApplicationEventPublisher) ignored -> { });
        var adapter = new WhatsAppChannelAdapter(new ObjectMapper());
        var webhook = new RawWebhook(UUID.randomUUID(), """
                {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"phone-42"},"messages":[
                  {"id":"wamid.batch-1","from":"5214521234567","text":{"body":"Hola"}},
                  {"id":"wamid.batch-2","from":"5214521234567","text":{"body":"Otra"}}
                ]}}]}]}
                """, "ref-batch");

        assertThatThrownBy(() -> service.accept(webhook, adapter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

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
        var event = (InboundMessageAccepted) published.getFirst();
        assertThat(event.receiptId()).isNotNull();
        assertThat(event.tenantId()).isNotNull();
        assertThat(event.conversationId()).isNotNull();
        var serialized = new ObjectMapper().findAndRegisterModules().valueToTree(event);
        assertThat(serialized.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "receiptId", "tenantId", "conversationId", "channel", "providerMessageId", "acceptedAt");
        assertThat(serialized.toString()).doesNotContain("Hola", "5214521234567", "participantId", "text");
    }
}
