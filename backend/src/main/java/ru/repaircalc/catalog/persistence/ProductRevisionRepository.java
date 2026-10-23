package ru.repaircalc.catalog.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRevisionRepository extends JpaRepository<ProductRevisionEntity, UUID> {
    List<ProductRevisionEntity> findByReleaseIdOrderByProductIdAsc(UUID releaseId);
    Optional<ProductRevisionEntity> findByReleaseIdAndProductId(UUID releaseId, UUID productId);
}
