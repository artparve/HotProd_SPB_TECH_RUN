package ru.repaircalc.catalog.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogReleaseRepository extends JpaRepository<CatalogReleaseEntity, UUID> {
    Optional<CatalogReleaseEntity> findByActiveTrue();
    Optional<CatalogReleaseEntity> findByVersion(String version);
    Optional<CatalogReleaseEntity> findByFingerprint(String fingerprint);
}
