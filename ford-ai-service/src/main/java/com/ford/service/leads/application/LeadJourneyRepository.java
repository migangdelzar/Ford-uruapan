package com.ford.service.leads.application;

import com.ford.service.leads.domain.LeadJourneySnapshot;

import java.util.UUID;

public interface LeadJourneyRepository {
    LeadJourneySnapshot getOrCreate(UUID tenantId, UUID conversationId);

    boolean update(UUID tenantId, UUID conversationId, long expectedVersion,
                   LeadJourneySnapshot snapshot, String providerMessageId);
}
