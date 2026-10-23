package ru.repaircalc.catalog.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "source_record", schema = "repaircalc")
public class SourceRecordEntity {
    @Id
    private UUID id;
    @Column(name = "release_id", nullable = false)
    private UUID releaseId;
    @Column(name = "product_id", nullable = false)
    private UUID productId;
    @Column(name = "source_namespace", nullable = false, columnDefinition = "text")
    private String sourceNamespace;
    @Column(name = "external_id", nullable = false, columnDefinition = "text")
    private String externalId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String raw;

    protected SourceRecordEntity() {
    }

    public UUID getId() { return id; }
    public UUID getReleaseId() { return releaseId; }
    public UUID getProductId() { return productId; }
    public String getSourceNamespace() { return sourceNamespace; }
    public String getExternalId() { return externalId; }
    public String getRaw() { return raw; }
}
