package com.ford.service.conversations.api;

import com.ford.service.channels.domain.Channel;
import org.springframework.modulith.NamedInterface;

import java.util.UUID;

@NamedInterface
public record OutboundDeliveryRequested(
        UUID eventId,
        UUID tenantId,
        UUID conversationId,
        Channel channel,
        String recipientId,
        String text) {

    public OutboundDeliveryRequested(UUID tenantId, UUID conversationId, Channel channel,
                                     String recipientId, String text) {
        this(UUID.randomUUID(), tenantId, conversationId, channel, recipientId, text);
    }
}
