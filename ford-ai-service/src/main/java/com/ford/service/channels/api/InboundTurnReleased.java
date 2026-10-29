package com.ford.service.channels.api;

import com.ford.service.channels.domain.Channel;
import org.springframework.modulith.NamedInterface;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes a conversation turn whose per-conversation debounce window has elapsed.
 * The price of the delay is paid here, so the conversation consumer processes exactly
 * once per burst instead of on the original delivery.
 */
@NamedInterface
public record InboundTurnReleased(
        UUID receiptId,
        UUID tenantId,
        UUID conversationId,
        Channel channel,
        String providerMessageId,
        Instant acceptedAt) {

    public static InboundTurnReleased from(InboundMessageAccepted accepted) {
        return new InboundTurnReleased(accepted.receiptId(), accepted.tenantId(), accepted.conversationId(),
                accepted.channel(), accepted.providerMessageId(), accepted.acceptedAt());
    }

    public InboundMessageAccepted asInboundMessageAccepted() {
        return new InboundMessageAccepted(receiptId, tenantId, conversationId, channel, providerMessageId, acceptedAt);
    }
}
