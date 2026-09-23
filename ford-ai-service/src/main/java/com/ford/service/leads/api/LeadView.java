package com.ford.service.leads.api;

import java.time.Instant;
import java.util.UUID;

public record LeadView(UUID id, UUID tenantId, UUID conversationId, String name, String phone,
                       String email, String preferredVehicle, String budgetRange,
                       String temperature, Integer score, Instant updatedAt) {
}
