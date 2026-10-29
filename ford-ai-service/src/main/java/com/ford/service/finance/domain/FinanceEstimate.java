package com.ford.service.finance.domain;

import com.ford.service.shared.Money;

public record FinanceEstimate(
        Money monthlyPayment,
        Money totalFinanced,
        Money totalCost,
        int termMonths,
        boolean binding) {
}
