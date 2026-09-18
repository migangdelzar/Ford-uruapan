package com.ford.service.finance.application;

import com.ford.service.finance.api.FinanceEstimateRequest;
import com.ford.service.finance.domain.FinanceEstimate;
import com.ford.service.finance.domain.FinanceTerms;

import java.util.Objects;

public final class FinanceService {

    private final DeterministicFinanceCalculator calculator;

    public FinanceService(DeterministicFinanceCalculator calculator) {
        this.calculator = Objects.requireNonNull(calculator);
    }

    public FinanceEstimate estimate(FinanceEstimateRequest request, FinanceTerms terms) {
        return calculator.calculate(request, terms);
    }
}
