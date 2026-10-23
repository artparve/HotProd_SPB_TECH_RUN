package ru.repaircalc.catalog.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product", schema = "repaircalc")
public class ProductEntity {
    @Id
    private UUID id;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProductEntity() {
    }

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
}
