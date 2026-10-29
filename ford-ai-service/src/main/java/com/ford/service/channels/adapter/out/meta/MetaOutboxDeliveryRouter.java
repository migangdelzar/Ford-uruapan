package com.ford.service.channels.adapter.out.meta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.application.port.out.ChannelDeliveryPort;
import com.ford.service.channels.application.port.out.CustomerWindowPort;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.DeliveryResult;
import com.ford.service.channels.domain.OutboundMessage;
import com.ford.service.channels.domain.ReplyOption;
import com.ford.service.outbox.NotificationResult;
import com.ford.service.outbox.OutboxDeliveryRouter;
import com.ford.service.outbox.OutboxEvent;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MetaOutboxDeliveryRouter implements OutboxDeliveryRouter {
    private final ObjectMapper objectMapper;
    private final Map<Channel, ChannelDeliveryPort> deliveryPorts;
    private final CustomerWindowPort customerWindows;

    public MetaOutboxDeliveryRouter(ObjectMapper objectMapper, Map<Channel, ChannelDeliveryPort> deliveryPorts) {
        this(objectMapper, deliveryPorts, null);
    }

    public MetaOutboxDeliveryRouter(ObjectMapper objectMapper, Map<Channel, ChannelDeliveryPort> deliveryPorts,
                                    CustomerWindowPort customerWindows) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.deliveryPorts = Map.copyOf(deliveryPorts);
        this.customerWindows = customerWindows;
    }

    @Override
    public NotificationResult deliver(OutboxEvent event) {
        if (!"OUTBOUND_DELIVERY_REQUESTED".equals(event.eventType())) {
            return NotificationResult.permanentFailure("Unsupported outbox event type: " + event.eventType());
        }
        try {
            var payload = objectMapper.readValue(event.payload(), DeliveryPayload.class);
            var channel = Channel.valueOf(payload.channel());
            var port = deliveryPorts.get(channel);
            if (port == null) {
                return NotificationResult.permanentFailure("No delivery port configured for " + channel);
            }
            if (customerWindows != null) {
                var conversationId = UUID.fromString(payload.conversationId());
                var window = customerWindows.find(event.tenantId(), conversationId);
                if (window.isEmpty() || window.get().channel() != channel || !window.get().open(Instant.now())) {
                    return NotificationResult.permanentFailure("Customer messaging window is closed or unavailable");
                }
            }
            var result = port.send(new OutboundMessage(event.tenantId(), channel, payload.recipientId(),
                    payload.text(), event.idempotencyKey(), payload.replyOptions()));
            return toNotificationResult(result);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return NotificationResult.permanentFailure(exception.getMessage());
        } catch (RuntimeException exception) {
            return NotificationResult.rejected(safeMessage(exception));
        }
    }

    private static NotificationResult toNotificationResult(DeliveryResult result) {
        if (result.accepted()) {
            return NotificationResult.delivered(result.providerMessageId());
        }
        return result.permanent()
                ? NotificationResult.permanentFailure(result.error())
                : NotificationResult.rejected(result.error());
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record DeliveryPayload(String conversationId, String channel, String recipientId, String text,
                                   java.util.List<ReplyOption> replyOptions) {
    }
}
