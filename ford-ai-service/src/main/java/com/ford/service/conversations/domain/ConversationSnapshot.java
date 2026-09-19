package com.ford.service.conversations.domain;

import com.ford.service.channels.domain.Channel;

import java.util.UUID;

public record ConversationSnapshot(
        UUID tenantId,
        UUID conversationId,
        Channel channel,
        String recipientId,
        ConversationState state,
        long version,
        String inboundText,
        String providerMessageId) {
}
