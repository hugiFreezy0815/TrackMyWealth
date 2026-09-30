package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.ReferencePackage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * US-01-04: the current reference package and how much of each kind of reference data is loaded.
 * All of it is global, unscoped data (NFR-LIC-007); the default categories are the shipped rows
 * ({@code workspace_id IS NULL}), which the category RLS policy shows to everyone.
 */
@Repository
public interface ReferencePackageRepository extends JpaRepository<ReferencePackage, UUID> {

  Optional<ReferencePackage> findByCurrentTrue();

  @Query(value = "SELECT count(*) FROM institution_catalogue", nativeQuery = true)
  long countCatalogueInstitutions();

  @Query(value = "SELECT count(*) FROM category WHERE workspace_id IS NULL", nativeQuery = true)
  long countDefaultCategories();

  @Query(value = "SELECT count(*) FROM category_source_mapping", nativeQuery = true)
  long countSourceCodeMappings();

  @Query(value = "SELECT count(*) FROM fallback_sector_taxonomy", nativeQuery = true)
  long countFallbackSectors();

  /** The GICS structure version in force today (FR-GICS-007), if any. */
  @Query(
      value =
          "SELECT structure_version FROM gics_structure_version"
              + " WHERE effective_from <= CURRENT_DATE"
              + " AND (effective_to IS NULL OR effective_to > CURRENT_DATE)"
              + " ORDER BY effective_from DESC LIMIT 1",
      nativeQuery = true)
  Optional<String> findCurrentGicsStructureVersion();
}
