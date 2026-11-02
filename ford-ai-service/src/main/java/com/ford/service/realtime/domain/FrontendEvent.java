package com.ford.service.realtime.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record FrontendEvent(UUID id,
                            UUID tenantId,
                            UUID aggregateId,
                            String type,
                            Instant occurredAt,
                            Map<String, Object> data) {
    public FrontendEvent {
        if (id == null || tenantId == null || type == null || occurredAt == null || data == null) {
            throw new IllegalArgumentException("frontend event identity and data are required");
        }
        data = Map.copyOf(data);
    }
}
