package ru.repaircalc.rules.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleSetRepository extends JpaRepository<RuleSetEntity, UUID> {
    Optional<RuleSetEntity> findByCatalogReleaseIdAndActiveTrue(UUID catalogReleaseId);
    Optional<RuleSetEntity> findByVersion(String version);
    Optional<RuleSetEntity> findByFingerprint(String fingerprint);
}
