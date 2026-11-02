package com.ford.service.channels.api;

import com.ford.service.channels.domain.Channel;
import org.springframework.modulith.NamedInterface;

import java.time.Instant;
import java.util.UUID;

@NamedInterface
public record InboundMessageAccepted(
        UUID receiptId,
        UUID tenantId,
        UUID conversationId,
        Channel channel,
        String providerMessageId,
        Instant acceptedAt) {
}
