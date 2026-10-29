package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;

public record PackageResult(long quantity, BigDecimal need, BigDecimal purchased,
                            BigDecimal remainder, Money total) {
}
