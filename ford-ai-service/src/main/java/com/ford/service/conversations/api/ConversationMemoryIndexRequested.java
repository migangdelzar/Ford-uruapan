package com.ford.service.conversations.api;

import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;

public record ConversationMemoryIndexRequested(
        ConversationMemoryKey key,
        ConversationMessage message) {
}
