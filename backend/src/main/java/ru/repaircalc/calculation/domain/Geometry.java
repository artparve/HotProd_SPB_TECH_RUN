package ru.repaircalc.calculation.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class Geometry {
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal MAX_SIDE = new BigDecimal("100");
    private static final BigDecimal MAX_HEIGHT = new BigDecimal("10");
    private static final BigDecimal MAX_OPENINGS = new BigDecimal("2000");

    private Geometry() {
    }

    public static Result calculate(BigDecimal length, BigDecimal width, BigDecimal height, BigDecimal openings) {
        List<String> fields = new ArrayList<>();
        if (!validSide(length)) {
            fields.add("length");
        }
        if (!validSide(width)) {
            fields.add("width");
        }
        if (height == null || height.compareTo(BigDecimal.ONE) < 0 || height.compareTo(MAX_HEIGHT) > 0) {
            fields.add("height");
        }
        if (openings == null || openings.signum() < 0 || openings.compareTo(MAX_OPENINGS) > 0) {
            fields.add("openings");
        }
        if (!fields.isEmpty()) {
            throw new CalculationException("needs_clarification", "The room dimensions are invalid.", fields);
        }
        BigDecimal area = length.multiply(width);
        BigDecimal perimeter = length.add(width).multiply(TWO);
        BigDecimal grossWalls = perimeter.multiply(height);
        if (openings.compareTo(grossWalls) >= 0) {
            throw CalculationException.needsClarification(
                    "The opening area must be smaller than the gross wall area.", "openings");
        }
        return new Result(area, perimeter, grossWalls.subtract(openings));
    }

    private static boolean validSide(BigDecimal side) {
        return side != null && side.signum() > 0 && side.compareTo(MAX_SIDE) <= 0;
    }

    public record Result(BigDecimal area, BigDecimal perimeter, BigDecimal walls) {
    }
}
