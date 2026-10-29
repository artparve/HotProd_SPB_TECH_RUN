package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;

public final class Reserve {
    private static final BigDecimal MAX_PERCENT = new BigDecimal("30");

    private Reserve() {
    }

    public static BigDecimal factor(BigDecimal percent) {
        if (percent == null || percent.signum() < 0 || percent.compareTo(MAX_PERCENT) > 0) {
            throw CalculationException.needsClarification("The reserve must be between 0 and 30 percent.", "reserve");
        }
        return BigDecimal.ONE.add(percent.movePointLeft(2));
    }
}
