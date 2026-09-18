package com.ford.service.shared;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {

    private static final Currency MXN = Currency.getInstance("MXN");

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (!MXN.equals(currency)) {
            throw new IllegalArgumentException("Only MXN is supported by this MVP");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("amount must have at most two decimals");
        }
    }
}
