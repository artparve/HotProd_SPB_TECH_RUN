package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public record CalculationInput(BigDecimal area, BigDecimal perimeter, BigDecimal height,
                               BigDecimal reserve, BigDecimal layers, BigDecimal quantity) {
    public CalculationInput {
        List<String> fields = new ArrayList<>();
        if (!positiveAtMost(area, "10000")) {
            fields.add("area");
        }
        if (!positiveAtMost(perimeter, "1000")) {
            fields.add("perimeter");
        }
        if (!within(height, "1", "10")) {
            fields.add("height");
        }
        if (!within(reserve, "0", "30")) {
            fields.add("reserve");
        }
        if (!within(layers, "1", "4") || layers.stripTrailingZeros().scale() > 0) {
            fields.add("layers");
        }
        if (!positiveAtMost(quantity, "5000")) {
            fields.add("quantity");
        }
        if (!fields.isEmpty()) {
            throw new CalculationException("needs_clarification", "The calculation input is invalid.", fields);
        }
    }

    private static boolean positiveAtMost(BigDecimal value, String maximum) {
        return value != null && value.signum() > 0 && value.compareTo(new BigDecimal(maximum)) <= 0;
    }

    private static boolean within(BigDecimal value, String minimum, String maximum) {
        return value != null && value.compareTo(new BigDecimal(minimum)) >= 0
                && value.compareTo(new BigDecimal(maximum)) <= 0;
    }
}
