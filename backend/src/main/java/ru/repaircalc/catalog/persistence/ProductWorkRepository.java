package ru.repaircalc.catalog.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductWorkRepository extends JpaRepository<ProductWorkEntity, ProductWorkId> {
    List<ProductWorkEntity> findByIdReleaseIdAndIdProductIdAndIdTaxonomyVersionOrderByIdWorkCodeAsc(
            UUID releaseId, UUID productId, String taxonomyVersion);

    List<ProductWorkEntity> findByIdReleaseIdAndIdTaxonomyVersionOrderByIdProductIdAscIdWorkCodeAsc(
            UUID releaseId, String taxonomyVersion);
}
