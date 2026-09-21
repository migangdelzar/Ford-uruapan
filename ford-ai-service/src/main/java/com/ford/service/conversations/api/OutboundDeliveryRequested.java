package com.ford.service.conversations.api;

import com.ford.service.channels.domain.Channel;

import java.util.UUID;

public record OutboundDeliveryRequested(
        UUID tenantId,
        UUID conversationId,
        Channel channel,
        String recipientId,
        String text) {
}
