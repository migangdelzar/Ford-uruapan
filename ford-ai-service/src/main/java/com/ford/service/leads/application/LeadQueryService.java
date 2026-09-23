package com.ford.service.leads.application;

import com.ford.service.leads.api.LeadPage;
import com.ford.service.leads.api.LeadView;

import java.util.Optional;
import java.util.UUID;

public interface LeadQueryService {
    LeadPage list(UUID tenantId, String cursor, int pageSize, String temperature);
    Optional<LeadView> find(UUID tenantId, UUID leadId);
}
