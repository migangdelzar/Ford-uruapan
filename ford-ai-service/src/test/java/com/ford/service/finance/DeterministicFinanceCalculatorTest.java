package com.ford.service.finance;

import com.ford.service.finance.application.DeterministicFinanceCalculator;
import com.ford.service.finance.api.FinanceEstimateRequest;
import com.ford.service.finance.domain.FinanceTerms;
import com.ford.service.shared.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DeterministicFinanceCalculatorTest {

    private static final Currency MXN = Currency.getInstance("MXN");
    private final DeterministicFinanceCalculator calculator = new DeterministicFinanceCalculator();

    @Test
    void calculatesNonBindingMonthlyPaymentIncludingFees() {
        var request = new FinanceEstimateRequest(
                new Money(new BigDecimal("300000.00"), MXN),
                new Money(new BigDecimal("60000.00"), MXN),
                36);
        var terms = new FinanceTerms(
                new BigDecimal("0.12"),
                new Money(new BigDecimal("3000.00"), MXN),
                new Money(new BigDecimal("12000.00"), MXN));

        var estimate = calculator.calculate(request, terms);

        assertThat(estimate.monthlyPayment().amount()).isEqualByComparingTo("8469.65");
        assertThat(estimate.totalFinanced().amount()).isEqualByComparingTo("255000.00");
        assertThat(estimate.termMonths()).isEqualTo(36);
        assertThat(estimate.binding()).isFalse();
    }

    @Test
    void calculatesZeroRateWithoutDividingByZero() {
        var request = new FinanceEstimateRequest(
                new Money(new BigDecimal("120000.00"), MXN),
                new Money(new BigDecimal("20000.00"), MXN),
                10);
        var terms = new FinanceTerms(
                BigDecimal.ZERO,
                new Money(new BigDecimal("0.00"), MXN),
                new Money(new BigDecimal("0.00"), MXN));

        assertThat(calculator.calculate(request, terms).monthlyPayment().amount())
                .isEqualByComparingTo("10000.00");
    }

    @Test
    void rejectsDownPaymentGreaterThanVehiclePrice() {
        var request = new FinanceEstimateRequest(
                new Money(new BigDecimal("100000.00"), MXN),
                new Money(new BigDecimal("100001.00"), MXN),
                12);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.calculate(request, new FinanceTerms(
                        new BigDecimal("0.12"),
                        new Money(new BigDecimal("0.00"), MXN),
                        new Money(new BigDecimal("0.00"), MXN))));
    }
}
