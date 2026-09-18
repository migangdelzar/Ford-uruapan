package com.ford.service.ingestion.domain;

import java.time.Instant;
import java.util.UUID;

public record SyncRequest(UUID tenantId, Instant requestedAt) {
    public SyncRequest {
        if (tenantId == null || requestedAt == null) {
            throw new IllegalArgumentException("tenantId and requestedAt are required");
        }
    }
}
