package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Money(BigDecimal amount) {
    public Money {
        if (amount == null || amount.signum() <= 0) {
            throw CalculationException.needsClarification("The price must be a positive amount.", "price");
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw CalculationException.needsClarification("The price must contain whole kopecks.", "price");
        }
    }
}
