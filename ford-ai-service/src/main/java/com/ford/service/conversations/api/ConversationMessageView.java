package com.ford.service.conversations.api;

import com.ford.service.conversations.domain.ConversationMessage.Direction;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ConversationMessageView(
        UUID id,
        UUID conversationId,
        String providerMessageId,
        Direction direction,
        String content,
        String deliveryStatus,
        ParticipantView sender,
        Instant receivedAt,
        Instant sentAt,
        Map<String, Object> metadata) {

    public ConversationMessageView {
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }
}
