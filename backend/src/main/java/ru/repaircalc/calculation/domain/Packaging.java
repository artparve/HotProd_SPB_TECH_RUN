package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Packaging {
    public static final long MAX_SAFE_QUANTITY = 9_007_199_254_740_991L;
    private static final BigDecimal MAX_SAFE_DECIMAL = BigDecimal.valueOf(MAX_SAFE_QUANTITY);

    private Packaging() {
    }

    public static PackageResult calculate(BigDecimal need, BigDecimal packageSize, Money price) {
        if (need == null || need.signum() <= 0) {
            throw CalculationException.needsClarification("The required amount must be positive.", "need");
        }
        if (packageSize == null || packageSize.signum() <= 0) {
            throw CalculationException.needsClarification("A positive package size must be known.", "packageSize");
        }
        if (price == null) {
            throw CalculationException.needsClarification("The price must be known.", "price");
        }
        BigDecimal count = need.divide(packageSize, 0, RoundingMode.CEILING);
        if (count.compareTo(MAX_SAFE_DECIMAL) > 0) {
            throw CalculationException.needsClarification(
                    "The required package count exceeds the supported range.", "quantity");
        }
        long quantity = count.longValueExact();
        BigDecimal purchased = packageSize.multiply(count);
        BigDecimal remainder = purchased.subtract(need);
        Money total = new Money(price.amount().multiply(count));
        return new PackageResult(quantity, need, purchased, remainder, total);
    }
}
