package com.ford.service.conversations.application.port.out;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.CommandReceipt;
import com.ford.service.conversations.domain.ConversationState;

import java.util.Optional;
import java.util.UUID;

public interface OperatorConversationCommandRepository {
    Optional<CommandTarget> find(UUID tenantId, UUID conversationId);

    boolean bumpVersionForMemoryRestart(UUID tenantId, UUID conversationId, long expectedVersion);

    CommandReceipt appendMessage(CommandMessage message);

    record CommandTarget(UUID tenantId, UUID conversationId, Channel channel,
                         String recipientId, ConversationState state, long version) {
    }

    record CommandMessage(UUID tenantId, UUID conversationId, UUID operatorId,
                          UUID messageId, String providerMessageId, String content,
                          boolean internalNote, long expectedVersion) {
    }
}
