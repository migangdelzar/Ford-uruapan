package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.api.ConversationFilter;
import com.ford.service.conversations.api.ConversationMessagePage;
import com.ford.service.conversations.api.ConversationPage;
import com.ford.service.conversations.api.ConversationView;

import java.util.Optional;
import java.util.UUID;

public interface OperatorConversationRepository {
    ConversationPage search(ConversationFilter filter);

    Optional<ConversationView> find(UUID tenantId, UUID conversationId);

    ConversationMessagePage messages(UUID tenantId, UUID conversationId, String cursor, int pageSize);
}
