package com.ford.service.finance.application.port.out;

import com.ford.service.finance.domain.FinanceTerms;

import java.util.Optional;
import java.util.UUID;

public interface FinanceTermsRepository {
    Optional<FinanceTerms> findActive(UUID tenantId, String productCode, int termMonths);
}
