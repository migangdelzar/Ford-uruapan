package com.ford.service.conversations.application.port.out;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.CommandReceipt;
import com.ford.service.conversations.domain.ConversationState;

import java.util.Optional;
import java.util.UUID;

public interface OperatorConversationCommandRepository {
    Optional<CommandTarget> find(UUID tenantId, UUID conversationId);

    boolean messageExists(UUID tenantId, UUID conversationId, String providerMessageId);

    boolean bumpVersionForMemoryRestart(UUID tenantId, UUID conversationId, long expectedVersion);

    CommandReceipt appendMessage(CommandMessage message);

    record CommandTarget(UUID tenantId, UUID conversationId, Channel channel,
                         String recipientId, ConversationState state, long version, boolean bindingEnabled) {
        public CommandTarget(UUID tenantId, UUID conversationId, Channel channel,
                             String recipientId, ConversationState state, long version) {
            this(tenantId, conversationId, channel, recipientId, state, version, true);
        }
    }

    record CommandMessage(UUID tenantId, UUID conversationId, UUID operatorId,
                          UUID messageId, String providerMessageId, String content,
                          boolean internalNote, long expectedVersion) {
    }
}
