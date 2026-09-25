package com.ford.service.ai.adapter.in.tool;

import com.ford.service.finance.api.FinanceEstimateRequest;
import com.ford.service.finance.application.FinanceOperations;
import com.ford.service.finance.application.port.out.FinanceTermsRepository;
import com.ford.service.finance.domain.FinanceEstimate;
import com.ford.service.shared.Money;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public final class FinanceEstimateTool {

    private static final Currency MXN = Currency.getInstance("MXN");

    private final FinanceOperations finance;
    private final FinanceTermsRepository termsRepository;

    public FinanceEstimateTool(FinanceOperations finance, FinanceTermsRepository termsRepository) {
        this.finance = Objects.requireNonNull(finance);
        this.termsRepository = Objects.requireNonNull(termsRepository);
    }

    @Tool(name = "finance_estimate", description = "Calcula una mensualidad estimada no vinculante con términos vigentes.")
    public FinanceEstimate estimate(String productCode, BigDecimal vehiclePrice, BigDecimal downPayment,
                                    int termMonths, ToolContext context) {
        var tenantId = tenantId(context);
        var terms = termsRepository.findActive(tenantId, productCode, termMonths)
                .orElseThrow(() -> new IllegalArgumentException("No active finance terms for the requested product"));
        var request = new FinanceEstimateRequest(money(vehiclePrice), money(downPayment), termMonths);
        return finance.estimate(request, terms);
    }

    private static Money money(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("money amount is required");
        }
        return new Money(amount.setScale(2, RoundingMode.HALF_UP), MXN);
    }

    private static UUID tenantId(ToolContext context) {
        if (context == null || context.getContext().get("tenantId") == null) {
            throw new IllegalArgumentException("tenantId tool context is required");
        }
        return UUID.fromString(context.getContext().get("tenantId").toString());
    }
}
