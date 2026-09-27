package com.ford.service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.OutboundDeliveryRequested;
import com.ford.service.conversations.application.OutboundDeliveryOutboxListener;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboundDeliveryOutboxListenerTest {

    @Test
    void persistsOutboundDeliveryRequestAsDurableOutboxEvent() {
        var publisher = mock(OutboxPublisher.class);
        var listener = new OutboundDeliveryOutboxListener(publisher, new ObjectMapper());
        var event = new OutboundDeliveryRequested(
                UUID.randomUUID(), UUID.randomUUID(), Channel.WHATSAPP, "recipient-1", "Hola");

        listener.on(event);

        var captor = forClass(OutboxEvent.class);
        verify(publisher).publish(captor.capture());
        var outboxEvent = captor.getValue();
        assertThat(outboxEvent.id()).isEqualTo(event.eventId());
        assertThat(outboxEvent.tenantId()).isEqualTo(event.tenantId());
        assertThat(outboxEvent.aggregateId()).isEqualTo(event.conversationId());
        assertThat(outboxEvent.eventType()).isEqualTo("OUTBOUND_DELIVERY_REQUESTED");
        assertThat(outboxEvent.payload()).contains("\"channel\":\"WHATSAPP\"")
                .contains("\"recipientId\":\"recipient-1\"")
                .contains("\"text\":\"Hola\"");
        assertThat(outboxEvent.idempotencyKey()).isEqualTo("outbox:" + event.eventId());
        assertThat(outboxEvent.status()).isEqualTo(OutboxEvent.Status.PENDING);
    }
}
