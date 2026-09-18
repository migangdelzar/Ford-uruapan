package com.ford.service.shared;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MoneyTest {

    private static final Currency MXN = Currency.getInstance("MXN");

    @Test
    void acceptsMexicanPesosWithAtMostTwoDecimals() {
        var money = new Money(new BigDecimal("123.45"), MXN);

        assertThat(money.amount()).isEqualByComparingTo("123.45");
        assertThat(money.currency()).isEqualTo(MXN);
    }

    @Test
    void rejectsMoreThanTwoDecimalsInsteadOfSilentlyRounding() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Money(new BigDecimal("123.456"), MXN));
    }

    @Test
    void rejectsNonMxnValuesForThisMvp() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Money(new BigDecimal("10.00"), Currency.getInstance("USD")));
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Money(new BigDecimal("-1.00"), MXN));
    }
}
