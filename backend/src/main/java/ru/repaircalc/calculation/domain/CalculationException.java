package ru.repaircalc.calculation.domain;

import java.util.List;
import java.util.Objects;

public final class CalculationException extends RuntimeException {
    private final String code;
    private final List<String> fields;

    public CalculationException(String code, String message, List<String> fields) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
        this.fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public String code() {
        return code;
    }

    public List<String> fields() {
        return fields;
    }

    public static CalculationException needsClarification(String message, String... fields) {
        return new CalculationException("needs_clarification", message, List.of(fields));
    }
}
