package ru.repaircalc.catalog.persistence;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_work", schema = "repaircalc")
public class ProductWorkEntity {
    @EmbeddedId
    private ProductWorkId id;

    @Column(name = "mapping_version", nullable = false, columnDefinition = "text")
    private String mappingVersion;

    @Column(name = "evidence", nullable = false, columnDefinition = "text")
    private String evidence;

    @Column(name = "source", nullable = false, columnDefinition = "text")
    private String source;

    protected ProductWorkEntity() {
    }

    public ProductWorkEntity(ProductWorkId id, String mappingVersion, String evidence, String source) {
        this.id = Objects.requireNonNull(id, "id");
        this.mappingVersion = Objects.requireNonNull(mappingVersion, "mappingVersion");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.source = Objects.requireNonNull(source, "source");
    }

    public ProductWorkId getId() {
        return id;
    }

    public String getMappingVersion() {
        return mappingVersion;
    }

    public String getEvidence() {
        return evidence;
    }

    public String getSource() {
        return source;
    }
}
