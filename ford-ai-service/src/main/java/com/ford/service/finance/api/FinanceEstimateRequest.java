package com.ford.service.finance.api;

import com.ford.service.shared.Money;

import java.util.Objects;

public record FinanceEstimateRequest(Money vehiclePrice, Money downPayment, int termMonths) {

    public FinanceEstimateRequest {
        Objects.requireNonNull(vehiclePrice, "vehiclePrice must not be null");
        Objects.requireNonNull(downPayment, "downPayment must not be null");
        if (termMonths <= 0 || termMonths > 84) {
            throw new IllegalArgumentException("termMonths must be between 1 and 84");
        }
    }
}
