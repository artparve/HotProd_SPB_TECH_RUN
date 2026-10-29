package ru.repaircalc.catalog.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRecordRepository extends JpaRepository<SourceRecordEntity, UUID> {
    List<SourceRecordEntity> findByReleaseIdOrderBySourceNamespaceAscExternalIdAsc(UUID releaseId);
    Optional<SourceRecordEntity> findByReleaseIdAndSourceNamespaceAndExternalId(
            UUID releaseId, String sourceNamespace, String externalId);
}
