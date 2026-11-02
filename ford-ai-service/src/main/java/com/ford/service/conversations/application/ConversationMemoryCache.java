package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;

import java.time.Duration;
import java.util.Optional;

/**
 * Application boundary for an optional hot conversation-memory cache.
 *
 * <p>The cache is never the durable source of truth. Implementations should
 * fail open so a cache outage cannot prevent a conversation from continuing.</p>
 */
public interface ConversationMemoryCache {

    Optional<ConversationMemoryContext> load(ConversationMemoryKey key);

    void store(ConversationMemoryContext context, Duration ttl);

    void evict(ConversationMemoryKey key);
}
