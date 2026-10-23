package ru.repaircalc.catalog.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkTypeRepository extends JpaRepository<WorkTypeEntity, WorkTypeId> {
    List<WorkTypeEntity> findByIdTaxonomyVersionOrderByIdCodeAsc(String taxonomyVersion);
}
