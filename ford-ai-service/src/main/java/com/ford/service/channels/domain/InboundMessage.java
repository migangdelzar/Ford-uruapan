package com.ford.service.channels.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record InboundMessage(
        UUID tenantId,
        Channel channel,
        String providerAccountId,
        String providerMessageId,
        String participantId,
        String text,
        List<Map<String, Object>> attachments,
        Instant receivedAt,
        String rawPayloadReference,
        UUID bindingId,
        UUID conversationId) {
    public InboundMessage(UUID tenantId, Channel channel, String providerMessageId,
                          String participantId, String text,
                          List<Map<String, Object>> attachments, Instant receivedAt,
                          String rawPayloadReference) {
        this(tenantId, channel, "unknown", providerMessageId, participantId, text, attachments,
                receivedAt, rawPayloadReference, null, null);
    }

    public InboundMessage {
        attachments = List.copyOf(attachments);
    }
}
