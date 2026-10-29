package ru.repaircalc.common.web;

import java.util.Objects;

/** A safe public diagnostic for a dependency that cannot currently serve the operation. */
public final class ServiceUnavailableException extends RuntimeException {
    private final String code;

    public ServiceUnavailableException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
