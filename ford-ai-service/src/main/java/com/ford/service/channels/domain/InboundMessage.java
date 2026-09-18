package com.ford.service.channels.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record InboundMessage(
        UUID tenantId,
        Channel channel,
        String providerMessageId,
        String participantId,
        String text,
        List<Map<String, Object>> attachments,
        Instant receivedAt,
        String rawPayloadReference) {
    public InboundMessage {
        attachments = List.copyOf(attachments);
    }
}
