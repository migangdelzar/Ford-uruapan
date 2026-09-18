package com.ford.service.channels.domain;

import java.util.UUID;

public record OutboundMessage(UUID tenantId, Channel channel, String recipientId, String text) {
}
