package com.ford.service.conversations.application;

import com.ford.service.ai.application.DecisionTraceEvent;

import java.util.List;
import java.util.UUID;

public interface DecisionTraceRepository {
    void append(UUID tenantId, UUID conversationId, UUID receiptId, List<DecisionTraceEvent> events);

    List<DecisionTraceEvent> list(UUID tenantId, UUID conversationId, UUID receiptId);
}
