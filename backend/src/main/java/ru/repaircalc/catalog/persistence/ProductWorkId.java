package ru.repaircalc.catalog.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class ProductWorkId implements Serializable {
    @Column(name = "release_id", nullable = false)
    private UUID releaseId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "taxonomy_version", nullable = false, columnDefinition = "text")
    private String taxonomyVersion;

    @Column(name = "work_code", nullable = false, columnDefinition = "text")
    private String workCode;

    protected ProductWorkId() {
    }

    public ProductWorkId(UUID releaseId, UUID productId, String taxonomyVersion, String workCode) {
        this.releaseId = Objects.requireNonNull(releaseId, "releaseId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.taxonomyVersion = Objects.requireNonNull(taxonomyVersion, "taxonomyVersion");
        this.workCode = Objects.requireNonNull(workCode, "workCode");
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getTaxonomyVersion() {
        return taxonomyVersion;
    }

    public String getWorkCode() {
        return workCode;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProductWorkId that)) {
            return false;
        }
        return Objects.equals(releaseId, that.releaseId)
                && Objects.equals(productId, that.productId)
                && Objects.equals(taxonomyVersion, that.taxonomyVersion)
                && Objects.equals(workCode, that.workCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(releaseId, productId, taxonomyVersion, workCode);
    }
}
