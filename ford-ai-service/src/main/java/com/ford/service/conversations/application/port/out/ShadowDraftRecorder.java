package com.ford.service.conversations.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Records a MÍA shadow draft for operator review. Implementations must never
 * deliver the draft to a customer or write a channel outbox record.
 */
@FunctionalInterface
public interface ShadowDraftRecorder {

    void record(UUID tenantId, UUID conversationId, UUID receiptId,
                String inboundProviderMessageId, String text, Instant createdAt);

    static ShadowDraftRecorder disabled() {
        return (tenantId, conversationId, receiptId, inboundProviderMessageId, text, createdAt) -> {
        };
    }
}
