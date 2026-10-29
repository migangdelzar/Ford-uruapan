package com.ford.service.outbox;

import java.util.UUID;

public record Notification(UUID tenantId, String eventType, String payload, String idempotencyKey) {
    public Notification(UUID tenantId, String eventType, String payload) {
        this(tenantId, eventType, payload, null);
    }

}
