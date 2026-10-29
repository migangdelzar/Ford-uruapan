package com.ford.service.finance.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record AvailableFinancePlan(String lenderCode, String productCode, String name, int termMonths,
                                   BigDecimal annualRate) {
    public AvailableFinancePlan {
        Objects.requireNonNull(lenderCode, "lenderCode must not be null");
        Objects.requireNonNull(productCode, "productCode must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(annualRate, "annualRate must not be null");
        if (termMonths <= 0) throw new IllegalArgumentException("termMonths must be positive");
    }
}
