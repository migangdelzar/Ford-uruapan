package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.application.ConversationTurnState;

import java.time.Instant;
import java.util.UUID;

public interface ConversationTurnLedgerPort {
    void recordOutcome(UUID tenantId, UUID conversationId, UUID receiptId, String providerMessageId,
                       ConversationTurnState state, String failureCategory, Instant occurredAt);

    void recordReview(UUID tenantId, UUID conversationId, UUID receiptId, String reasonCode, Instant createdAt);

    /** Count terminal substantive failures since the latest committed reply; retries are ignored. */
    default int consecutiveSubstantiveFailures(UUID tenantId, UUID conversationId) { return 0; }
}
