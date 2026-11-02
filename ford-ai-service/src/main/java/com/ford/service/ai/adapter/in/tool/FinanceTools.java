package com.ford.service.ai.adapter.in.tool;

import com.ford.service.finance.application.FinanceOperations;

import java.util.Objects;

public final class FinanceTools {

    private final FinanceOperations financeService;

    public FinanceTools(FinanceOperations financeService) {
        this.financeService = Objects.requireNonNull(financeService);
    }

    public FinanceOperations financeService() {
        return financeService;
    }
}
