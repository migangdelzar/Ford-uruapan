package com.ford.service.conversations.application.port.in;

import com.ford.service.conversations.api.ConversationPage;
import com.ford.service.conversations.api.ConversationMessagePage;
import com.ford.service.conversations.api.ConversationView;
import com.ford.service.conversations.api.ConversationFilter;

import java.util.Optional;
import java.util.UUID;

public interface ConversationQuery {
    ConversationPage search(ConversationFilter filter);

    Optional<ConversationView> find(UUID tenantId, UUID conversationId);

    ConversationMessagePage messages(UUID tenantId, UUID conversationId, String cursor, int pageSize);
}
