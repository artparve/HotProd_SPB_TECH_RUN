package ru.repaircalc.catalog.persistence;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class WorkTypeId implements Serializable {
    @Column(name = "taxonomy_version", nullable = false, columnDefinition = "text")
    private String taxonomyVersion;

    @Column(name = "code", nullable = false, columnDefinition = "text")
    private String code;

    protected WorkTypeId() {
    }

    public WorkTypeId(String taxonomyVersion, String code) {
        this.taxonomyVersion = Objects.requireNonNull(taxonomyVersion, "taxonomyVersion");
        this.code = Objects.requireNonNull(code, "code");
    }

    public String getTaxonomyVersion() {
        return taxonomyVersion;
    }

    public String getCode() {
        return code;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkTypeId that)) {
            return false;
        }
        return Objects.equals(taxonomyVersion, that.taxonomyVersion) && Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taxonomyVersion, code);
    }
}
