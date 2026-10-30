package ru.repaircalc.admin;

import java.util.List;

public final class AdminException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<Detail> details;

    public AdminException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public AdminException(int status, String code, String message, List<Detail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
    }

    public int status() { return status; }
    public String code() { return code; }
    public List<Detail> details() { return details; }
    public record Detail(String field, String code, String message) { }
}
