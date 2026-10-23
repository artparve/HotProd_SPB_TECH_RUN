package ru.repaircalc.common.web;

import java.util.List;
import java.util.Objects;

/** A domain rejection whose code, message, and field paths are safe to return to the caller. */
public final class DomainException extends RuntimeException {
    private final String code;
    private final List<String> fields;

    public DomainException(String code, String message, List<String> fields) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
        this.fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public DomainException(String code, String message) {
        this(code, message, List.of());
    }

    public String code() {
        return code;
    }

    public List<String> fields() {
        return fields;
    }
}
