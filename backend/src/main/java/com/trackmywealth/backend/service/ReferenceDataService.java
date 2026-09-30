package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ReferenceDataResponse;
import com.trackmywealth.backend.entity.ReferencePackage;
import com.trackmywealth.backend.repository.CategoryRepository;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import com.trackmywealth.backend.repository.ReferencePackageRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-01-04/FR-REF-001/010: which reference-data package is loaded, for administrators. Only reading
 * what the baseline migrations (V19, V43) - and later EPIC 32's package import - put in place.
 * Reachable only by {@code SYSTEM_ADMINISTRATOR} ({@code SecurityConfig}); it touches no
 * workspace's data, so administration rights never widen financial access here (FR-TEN-007).
 */
@Service
public class ReferenceDataService {

  private final ReferencePackageRepository referencePackageRepository;
  private final InstitutionCatalogueRepository institutionCatalogueRepository;
  private final CategoryRepository categoryRepository;

  public ReferenceDataService(
      ReferencePackageRepository referencePackageRepository,
      InstitutionCatalogueRepository institutionCatalogueRepository,
      CategoryRepository categoryRepository) {
    this.referencePackageRepository = referencePackageRepository;
    this.institutionCatalogueRepository = institutionCatalogueRepository;
    this.categoryRepository = categoryRepository;
  }

  @Transactional(readOnly = true)
  public ReferenceDataResponse current() {
    ReferencePackage current =
        referencePackageRepository
            .findByCurrentTrue()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No current reference package: V19/V43 always leave one."));
    return new ReferenceDataResponse(
        current.getPackageVersion(),
        current.getPublicationDate(),
        current.getImportedAt(),
        current.getImportedBy(),
        new ReferenceDataResponse.Contents(
            institutionCatalogueRepository.count(),
            // Shipped rows: the category RLS policy shows workspace_id IS NULL rows to everyone.
            categoryRepository.countByWorkspaceIdIsNull(),
            referencePackageRepository.countSourceCodeMappings(),
            referencePackageRepository.countFallbackSectors(),
            referencePackageRepository.findCurrentGicsStructureVersion().orElse(null)),
        // FR-REF-011 warns about effective-dated values missing for the current period; no such
        // reference data is loaded yet (EPIC 32), so there is nothing to warn about.
        List.of());
  }
}
