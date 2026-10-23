package ru.repaircalc.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

public record ApiError(
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> fields) {

    public ApiError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public ApiError(String code, String message) {
        this(code, message, List.of());
    }
}
