package ru.repaircalc.catalog;

import java.util.List;
import java.util.UUID;

public record CatalogSnapshot(UUID releaseId, String version, String fingerprint, String taxonomyVersion,
                              List<ProductView> products) {
    public CatalogSnapshot { products = List.copyOf(products); }
}
