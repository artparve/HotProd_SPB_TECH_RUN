package ru.repaircalc.catalog.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductWorkRepository extends JpaRepository<ProductWorkEntity, ProductWorkId> {
    @Query(value = """
            select w.* from repaircalc.product_work w
            join repaircalc.catalog_release c on c.id = w.release_id
            where w.release_id = :releaseId and c.status = 'PUBLISHED'
              and w.taxonomy_version = (c.manifest->>'taxonomyVersion')
            order by w.product_id, w.work_code
            """, nativeQuery = true)
    List<ProductWorkEntity> findPublishedAssignments(@Param("releaseId") UUID releaseId);

    List<ProductWorkEntity> findByIdReleaseIdAndIdProductIdAndIdTaxonomyVersionOrderByIdWorkCodeAsc(
            UUID releaseId, UUID productId, String taxonomyVersion);

    List<ProductWorkEntity> findByIdReleaseIdAndIdTaxonomyVersionOrderByIdProductIdAscIdWorkCodeAsc(
            UUID releaseId, String taxonomyVersion);
}
