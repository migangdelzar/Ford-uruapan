package com.ford.service.channels.domain;

import java.util.UUID;
import java.util.List;

public record OutboundMessage(UUID tenantId, Channel channel, String recipientId, String text,
                              String idempotencyKey, List<ReplyOption> replyOptions) {
    public OutboundMessage(UUID tenantId, Channel channel, String recipientId, String text,
                           String idempotencyKey) {
        this(tenantId, channel, recipientId, text, idempotencyKey, List.of());
    }

    public OutboundMessage(UUID tenantId, Channel channel, String recipientId, String text) {
        this(tenantId, channel, recipientId, text, null, List.of());
    }

    public OutboundMessage {
        replyOptions = List.copyOf(replyOptions == null ? List.of() : replyOptions);
    }
}
