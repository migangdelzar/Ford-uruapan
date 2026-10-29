package com.ford.service.conversations.api;

import java.time.Instant;
import java.util.UUID;

public record CommandReceipt(
        UUID messageId,
        UUID conversationId,
        boolean accepted,
        String deliveryStatus,
        long conversationVersion,
        boolean internalNote,
        Instant acceptedAt) {
}
