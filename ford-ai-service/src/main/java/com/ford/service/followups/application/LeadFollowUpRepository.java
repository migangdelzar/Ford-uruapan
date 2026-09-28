package com.ford.service.followups.application;

import com.ford.service.followups.domain.LeadFollowUp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeadFollowUpRepository {
    Optional<LeadFollowUp> find(UUID tenantId, UUID conversationId);
    void offer(UUID tenantId, UUID conversationId, String sourceMessageKey);
    boolean accept(UUID tenantId, UUID conversationId, String sourceMessageKey);
    boolean decline(UUID tenantId, UUID conversationId);
    boolean claim(UUID tenantId, UUID followUpId);
    List<LeadFollowUp> queued(UUID tenantId);
}
