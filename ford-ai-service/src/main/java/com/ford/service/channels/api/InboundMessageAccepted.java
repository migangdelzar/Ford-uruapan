package com.ford.service.channels.api;

import com.ford.service.channels.domain.Channel;

import java.time.Instant;
import java.util.UUID;

public record InboundMessageAccepted(
        UUID receiptId,
        UUID tenantId,
        UUID conversationId,
        Channel channel,
        String providerMessageId,
        Instant acceptedAt) {
}
