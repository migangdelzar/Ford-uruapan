package com.ford.service.finance.application;

import com.ford.service.finance.api.FinanceEstimateRequest;
import com.ford.service.finance.domain.FinanceEstimate;
import com.ford.service.finance.domain.FinanceTerms;
import com.ford.service.shared.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

public final class DeterministicFinanceCalculator {

    private static final Currency MXN = Currency.getInstance("MXN");

    public FinanceEstimate calculate(FinanceEstimateRequest request, FinanceTerms terms) {
        var price = request.vehiclePrice().amount();
        var downPayment = request.downPayment().amount();
        if (downPayment.compareTo(price) > 0) {
            throw new IllegalArgumentException("downPayment cannot exceed vehiclePrice");
        }

        var fees = terms.openingFee().amount().add(terms.insuranceFee().amount());
        var totalFinanced = price.subtract(downPayment).add(fees).setScale(2, RoundingMode.HALF_UP);
        var monthlyRate = terms.annualRate().divide(BigDecimal.valueOf(12), 16, RoundingMode.HALF_UP);
        var monthlyPayment = monthlyRate.signum() == 0
                ? totalFinanced.divide(BigDecimal.valueOf(request.termMonths()), 2, RoundingMode.HALF_UP)
                : annuityPayment(totalFinanced, monthlyRate, request.termMonths());
        var totalCost = monthlyPayment.multiply(BigDecimal.valueOf(request.termMonths()))
                .add(downPayment)
                .setScale(2, RoundingMode.HALF_UP);

        return new FinanceEstimate(
                money(monthlyPayment),
                money(totalFinanced),
                money(totalCost),
                request.termMonths(),
                false);
    }

    private static BigDecimal annuityPayment(BigDecimal principal, BigDecimal monthlyRate, int months) {
        double rate = monthlyRate.doubleValue();
        double payment = principal.doubleValue() * rate / (1 - Math.pow(1 + rate, -months));
        return BigDecimal.valueOf(payment).setScale(2, RoundingMode.HALF_UP);
    }

    private static Money money(BigDecimal amount) {
        return new Money(amount.setScale(2, RoundingMode.HALF_UP), MXN);
    }
}
