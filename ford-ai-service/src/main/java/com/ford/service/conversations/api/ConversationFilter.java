package com.ford.service.conversations.api;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.domain.ConversationState;

import java.util.UUID;

public record ConversationFilter(
        UUID tenantId,
        String cursor,
        int pageSize,
        ConversationState state,
        Channel channel,
        String search,
        UUID assignedOperatorId) {

    public ConversationFilter {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenant id is required");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("page size must be between 1 and 100");
        }
        search = search == null || search.isBlank() ? null : search.trim();
        if (search != null && search.length() > 200) {
            throw new IllegalArgumentException("search must not exceed 200 characters");
        }
    }
}
