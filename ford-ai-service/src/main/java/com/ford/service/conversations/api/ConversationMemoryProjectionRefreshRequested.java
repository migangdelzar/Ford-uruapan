package com.ford.service.conversations.api;

import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;

public record ConversationMemoryProjectionRefreshRequested(
        ConversationMemoryKey key,
        MemoryProjectionUpdate update,
        long expectedVersion) {
}
