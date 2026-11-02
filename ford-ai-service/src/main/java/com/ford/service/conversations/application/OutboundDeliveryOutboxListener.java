package com.ford.service.conversations.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.api.OutboundDeliveryRequested;
import com.ford.service.outbox.OutboxEvent;
import com.ford.service.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "ford.outbox.enabled", havingValue = "true")
public class OutboundDeliveryOutboxListener {

    private static final Logger log = LoggerFactory.getLogger(OutboundDeliveryOutboxListener.class);

    private final OutboxPublisher publisher;
    private final ObjectMapper objectMapper;

    public OutboundDeliveryOutboxListener(OutboxPublisher publisher, ObjectMapper objectMapper) {
        this.publisher = Objects.requireNonNull(publisher);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @ApplicationModuleListener
    public void on(OutboundDeliveryRequested event) {
        log.info("Outbound delivery event received: eventId={} conversationId={} channel={}",
                event.eventId(), event.conversationId(), event.channel());
        var outboxEvent = new OutboxEvent(
                event.eventId(),
                event.tenantId(),
                "conversation",
                event.conversationId(),
                "OUTBOUND_DELIVERY_REQUESTED",
                payload(event),
                OutboxEvent.Status.PENDING,
                0,
                Instant.now(),
                null);
        publisher.publish(outboxEvent);
        log.info("Outbound delivery event enqueued: eventId={} conversationId={} eventType={} status={}",
                event.eventId(), event.conversationId(), outboxEvent.eventType(), outboxEvent.status());
    }

    private String payload(OutboundDeliveryRequested event) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "conversationId", event.conversationId(),
                    "channel", event.channel().name(),
                    "recipientId", event.recipientId(),
                    "text", event.text()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize outbound delivery request", exception);
        }
    }
}
