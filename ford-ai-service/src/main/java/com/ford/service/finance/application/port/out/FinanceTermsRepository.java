package com.ford.service.finance.application.port.out;

import com.ford.service.finance.domain.FinanceTerms;
import com.ford.service.finance.domain.AvailableFinancePlan;
import org.springframework.modulith.NamedInterface;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

@NamedInterface
public interface FinanceTermsRepository {
    Optional<FinanceTerms> findActive(UUID tenantId, String productCode, int termMonths);

    List<AvailableFinancePlan> findAvailable(UUID tenantId);
}
