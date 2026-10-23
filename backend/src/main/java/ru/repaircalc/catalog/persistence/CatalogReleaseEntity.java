package ru.repaircalc.catalog.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "catalog_release", schema = "repaircalc")
public class CatalogReleaseEntity {
    @Id
    private UUID id;
    @Column(nullable = false, columnDefinition = "text")
    private String version;
    @Column(nullable = false, length = 64)
    private String fingerprint;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String manifest;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CatalogReleaseStatus status;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;

    protected CatalogReleaseEntity() {
    }

    public UUID getId() { return id; }
    public String getVersion() { return version; }
    public String getFingerprint() { return fingerprint; }
    public String getManifest() { return manifest; }
    public CatalogReleaseStatus getStatus() { return status; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
