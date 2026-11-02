package com.ford.service.channels.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InboundMessage(
        UUID tenantId,
        Channel channel,
        String providerAccountId,
        String providerMessageId,
        String participantId,
        String text,
        List<InboundAttachment> attachments,
        Instant receivedAt,
        String rawPayloadReference,
        UUID bindingId,
        UUID conversationId,
        InboundMessageKind kind) {
    public InboundMessage(UUID tenantId, Channel channel, String providerAccountId,
                          String providerMessageId, String participantId, String text,
                          List<InboundAttachment> attachments, Instant receivedAt,
                          String rawPayloadReference, UUID bindingId, UUID conversationId) {
        this(tenantId, channel, providerAccountId, providerMessageId, participantId, text,
                attachments, receivedAt, rawPayloadReference, bindingId, conversationId, InboundMessageKind.TEXT);
    }

    public InboundMessage(UUID tenantId, Channel channel, String providerMessageId,
                          String participantId, String text,
                          List<InboundAttachment> attachments, Instant receivedAt,
                          String rawPayloadReference) {
        this(tenantId, channel, "unknown", providerMessageId, participantId, text, attachments,
                receivedAt, rawPayloadReference, null, null, InboundMessageKind.TEXT);
    }

    public InboundMessage {
        attachments = List.copyOf(attachments == null ? List.of() : attachments);
        if (kind == null) {
            throw new IllegalArgumentException("inbound message kind is required");
        }
    }
}
