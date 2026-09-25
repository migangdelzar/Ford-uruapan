package com.ford.service.leads.application;

import com.ford.service.leads.domain.LeadJourneyProposal;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@NamedInterface
public class LeadJourneyService {
    private final LeadJourneyRepository repository;
    private final LeadJourneyPolicy policy;

    public LeadJourneyService(LeadJourneyRepository repository, LeadJourneyPolicy policy) {
        this.repository = Objects.requireNonNull(repository);
        this.policy = Objects.requireNonNull(policy);
    }

    public LeadJourneySnapshot load(UUID tenantId, UUID conversationId) {
        return repository.getOrCreate(tenantId, conversationId);
    }

    public boolean apply(UUID tenantId, UUID conversationId, LeadJourneySnapshot expectedSnapshot,
                         LeadJourneyProposal proposal, String currentMessage, boolean sensitiveDataDetected,
                         String providerMessageId) {
        if (proposal == null) {
            return false;
        }
        var updated = policy.apply(expectedSnapshot, proposal, currentMessage, sensitiveDataDetected,
                providerMessageId, Instant.now());
        if (updated == expectedSnapshot || updated == null) {
            return false;
        }
        if (!repository.update(tenantId, conversationId, expectedSnapshot.version(), updated, providerMessageId)) {
            throw new IllegalStateException("Lead journey update lost its version race");
        }
        return true;
    }
}
