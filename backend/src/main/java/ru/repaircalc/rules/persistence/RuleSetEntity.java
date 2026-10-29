package ru.repaircalc.rules.persistence;

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
@Table(name = "rule_set", schema = "repaircalc")
public class RuleSetEntity {
    @Id
    private UUID id;
    @Column(nullable = false, columnDefinition = "text")
    private String version;
    @Column(nullable = false, length = 64)
    private String fingerprint;
    @Column(name = "catalog_release_id", nullable = false)
    private UUID catalogReleaseId;
    @Column(name = "taxonomy_version", nullable = false, columnDefinition = "text")
    private String taxonomyVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String bundle;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String manifest;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RuleSetStatus status;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;

    protected RuleSetEntity() {
    }

    public UUID getId() { return id; }
    public String getVersion() { return version; }
    public String getFingerprint() { return fingerprint; }
    public UUID getCatalogReleaseId() { return catalogReleaseId; }
    public String getTaxonomyVersion() { return taxonomyVersion; }
    public String getBundle() { return bundle; }
    public String getManifest() { return manifest; }
    public RuleSetStatus getStatus() { return status; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
