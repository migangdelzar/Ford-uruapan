package com.ford.service.followups.domain;

import java.time.Instant;
import java.util.UUID;

public record LeadFollowUp(UUID id, UUID tenantId, UUID conversationId, Status status,
                           Instant offeredAt, Instant consentAt, String sourceMessageKey) {
    public enum Status { OFFERED, DECLINED, QUEUED, CLAIMED, CLOSED }
}
