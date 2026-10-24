package ru.repaircalc.calculation.domain;

public enum Unit {
    SQUARE_METRE("м²"),
    LITRE("л"),
    KILOGRAM("кг"),
    METRE("м"),
    PIECE("шт."),
    STRIP("полотен");

    private final String label;

    Unit(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Unit fromLabel(String label) {
        for (Unit unit : values()) {
            if (unit.label.equals(label)) {
                return unit;
            }
        }
        throw CalculationException.needsClarification("The measurement unit is not supported.", "unit");
    }
}
