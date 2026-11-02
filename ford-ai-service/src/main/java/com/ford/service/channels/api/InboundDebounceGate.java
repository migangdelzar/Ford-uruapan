package com.ford.service.channels.api;

import org.springframework.modulith.NamedInterface;

import java.util.UUID;

/**
 * Lets the conversation consumer defer a turn until the per-conversation debounce
 * window has elapsed. When no gate is configured the consumer processes immediately.
 */
@NamedInterface
public interface InboundDebounceGate {

    boolean isDue(UUID tenantId, UUID conversationId, UUID receiptId);
}
