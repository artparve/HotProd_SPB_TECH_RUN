package ru.repaircalc.ingestion;

/** Operator-facing diagnostic. Messages contain field paths, never raw input or DB errors. */
public final class CatalogImportException extends RuntimeException {
    public CatalogImportException(String message) {
        super(message);
    }
}
