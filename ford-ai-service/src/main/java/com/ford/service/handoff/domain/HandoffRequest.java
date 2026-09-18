package com.ford.service.handoff.domain;

import java.util.UUID;

public record HandoffRequest(UUID tenantId, UUID conversationId, UUID leadId, String reason) {
}
