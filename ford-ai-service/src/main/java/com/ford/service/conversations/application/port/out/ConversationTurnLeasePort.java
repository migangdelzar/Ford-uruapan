package com.ford.service.conversations.application.port.out;

import java.time.Duration;
import java.util.UUID;

public interface ConversationTurnLeasePort {
    boolean tryAcquire(UUID tenantId, UUID conversationId, String owner, Duration duration);
    void release(UUID tenantId, UUID conversationId, String owner);
}
