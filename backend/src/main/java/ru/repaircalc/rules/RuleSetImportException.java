package ru.repaircalc.rules;

/** A safe operator-facing rejection; no SQL or file contents are exposed. */
public final class RuleSetImportException extends RuntimeException {
    public RuleSetImportException(String message) {
        super(message);
    }
}
