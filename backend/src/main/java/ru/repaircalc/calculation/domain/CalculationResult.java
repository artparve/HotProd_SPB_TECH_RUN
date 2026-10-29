package ru.repaircalc.calculation.domain;

public record CalculationResult(PackageResult packages, String formula, String reason) {
}
