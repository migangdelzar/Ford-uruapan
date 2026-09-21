package com.ford.service.conversations.domain;

import com.ford.service.channels.domain.Channel;

import java.util.Objects;
import java.util.UUID;

public record ConversationMemoryKey(
        UUID tenantId,
        Channel channel,
        String providerAccountId,
        String participantId,
        UUID conversationId) {

    public ConversationMemoryKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(conversationId, "conversationId");
        if (providerAccountId == null || providerAccountId.isBlank()) {
            throw new IllegalArgumentException("providerAccountId is required");
        }
        if (participantId == null || participantId.isBlank()) {
            throw new IllegalArgumentException("participantId is required");
        }
    }
}
