package com.ford.service.channels.domain;

import java.util.UUID;

public record OutboundMessage(UUID tenantId, Channel channel, String recipientId, String text,
                              String idempotencyKey) {
    public OutboundMessage(UUID tenantId, Channel channel, String recipientId, String text) {
        this(tenantId, channel, recipientId, text, null);
    }
}
