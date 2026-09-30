package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.ReferencePackage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * US-01-04: the current reference package, and the counts of the reference data that has no
 * repository of its own - source-code mappings and the fallback sector taxonomy. The institution
 * catalogue and the default categories are counted through their own repositories. All of it is
 * global, unscoped data (NFR-LIC-007).
 */
@Repository
public interface ReferencePackageRepository extends JpaRepository<ReferencePackage, UUID> {

  Optional<ReferencePackage> findByCurrentTrue();

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
