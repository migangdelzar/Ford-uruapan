package com.ford.service.finance.application;

import com.ford.service.finance.api.FinanceEstimateRequest;
import com.ford.service.finance.domain.FinanceEstimate;
import com.ford.service.finance.domain.FinanceTerms;
import org.springframework.modulith.NamedInterface;

@NamedInterface
public interface FinanceOperations {

    FinanceEstimate estimate(FinanceEstimateRequest request, FinanceTerms terms);
}
