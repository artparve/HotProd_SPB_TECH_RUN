package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record CalculationProduct(UUID id, String kind, BigDecimal price, BigDecimal packageSize,
                                 String unit, String saleUnit, BigDecimal consumption,
                                 BigDecimal rollWidth, BigDecimal rollLength, BigDecimal rapport) {
}
