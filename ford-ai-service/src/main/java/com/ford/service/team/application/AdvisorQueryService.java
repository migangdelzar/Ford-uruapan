package com.ford.service.team.application;

import com.ford.service.operator.application.AuthenticatedOperator;
import com.ford.service.team.api.AdvisorView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AdvisorQueryService {
    private final AdvisorRepository repository;

    public AdvisorQueryService(AdvisorRepository repository) {
        this.repository = repository;
    }

    public List<AdvisorView> list(UUID tenantId, AuthenticatedOperator operator) {
        if (tenantId == null || operator == null || !operator.canAccess(tenantId)) {
            throw new SecurityException("operator is not authorized for tenant");
        }
        return repository.list(tenantId, operator.subject());
    }

    public interface AdvisorRepository {
        List<AdvisorView> list(UUID tenantId, String currentOperatorSubject);
    }
}
