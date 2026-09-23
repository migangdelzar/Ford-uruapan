package com.ford.service.conversations.api;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.domain.ConversationState;

import java.time.Instant;
import java.util.UUID;

public record ConversationView(
        UUID id,
        UUID tenantId,
        Channel channel,
        ConversationState state,
        long version,
        ParticipantView participant,
        UUID assignedOperatorId,
        UUID leadId,
        ConversationMessageView latestMessage,
        Instant updatedAt) {
}
