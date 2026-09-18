package com.ford.service.ai.adapter.in.tool;

import com.ford.service.finance.application.FinanceService;

import java.util.Objects;

public final class FinanceTools {

    private final FinanceService financeService;

    public FinanceTools(FinanceService financeService) {
        this.financeService = Objects.requireNonNull(financeService);
    }

    public FinanceService financeService() {
        return financeService;
    }
}
