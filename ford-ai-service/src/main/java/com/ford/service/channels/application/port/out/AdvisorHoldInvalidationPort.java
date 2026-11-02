package com.ford.service.channels.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Same-transaction boundary: a newly accepted inbound supersedes any live advisor hold. */
public interface AdvisorHoldInvalidationPort {
    void invalidateForInbound(UUID tenantId, UUID conversationId, Instant receivedAt);
}
