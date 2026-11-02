package com.ford.service.events.application;

import com.ford.service.events.api.AuditEventPage;

import java.util.UUID;

public interface AuditEventQueryService {
    AuditEventPage list(UUID tenantId, String cursor, int pageSize, String eventType);
}
