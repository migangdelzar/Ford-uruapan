package com.ford.service.conversations.application;

import com.ford.service.conversations.api.CommandReceipt;
import com.ford.service.conversations.api.SendMessageRequest;
import com.ford.service.operator.application.AuthenticatedOperator;

import java.util.UUID;

public interface ConversationCommandService {
    CommandReceipt sendMessage(AuthenticatedOperator operator, UUID tenantId, UUID conversationId,
                               long expectedVersion, String idempotencyKey, SendMessageRequest request);
}
