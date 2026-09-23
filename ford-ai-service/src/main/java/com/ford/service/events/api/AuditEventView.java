package com.ford.service.events.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventView(UUID id, UUID tenantId, String actorType, String actorId,
                             String eventType, String aggregateType, UUID aggregateId,
                             Map<String, Object> metadata, Instant occurredAt) {
    public AuditEventView {
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }
}
