package com.ford.service.finance.domain;

import com.ford.service.shared.Money;

import java.math.BigDecimal;
import java.util.Objects;

public record FinanceTerms(BigDecimal annualRate, Money openingFee, Money insuranceFee) {

    public FinanceTerms {
        Objects.requireNonNull(annualRate, "annualRate must not be null");
        Objects.requireNonNull(openingFee, "openingFee must not be null");
        Objects.requireNonNull(insuranceFee, "insuranceFee must not be null");
        if (annualRate.signum() < 0 || annualRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("annualRate must be between 0 and 1");
        }
    }
}
