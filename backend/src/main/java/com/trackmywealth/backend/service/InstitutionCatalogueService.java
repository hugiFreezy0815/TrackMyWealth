package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.InstitutionCatalogueEntrySummaryResponse;
import com.trackmywealth.backend.dto.PagingValues;
import com.trackmywealth.backend.entity.InstitutionCatalogue;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-04-01: search the shared institution catalogue (FR-INS-007). It reads only shared reference
 * data and relies on authentication alone; linking an entry to a portfolio is {@link
 * PortfolioService}'s.
 */
@Service
public class InstitutionCatalogueService {

  private final InstitutionCatalogueRepository institutionCatalogueRepository;

  public InstitutionCatalogueService(
      InstitutionCatalogueRepository institutionCatalogueRepository) {
    this.institutionCatalogueRepository = institutionCatalogueRepository;
  }

  @Transactional(readOnly = true)
  public Page<InstitutionCatalogueEntrySummaryResponse> searchCatalogue(
      String query, Pageable requested) {
    Pageable pageable = PagingValues.requireBounded(requested, "id");
    Page<InstitutionCatalogue> page =
        (query == null || query.isBlank())
            ? institutionCatalogueRepository.findByActiveTrue(pageable)
            : institutionCatalogueRepository.findByActiveTrueAndNameContainingIgnoreCase(
                query, pageable);
    return page.map(InstitutionCatalogueService::toSummary);
  }

  private static InstitutionCatalogueEntrySummaryResponse toSummary(
      InstitutionCatalogue catalogue) {
    return new InstitutionCatalogueEntrySummaryResponse(
        catalogue.getId(),
        catalogue.getName(),
        catalogue.getCountry(),
        catalogue.getInstitutionType(),
        catalogue.getIdentifier(),
        catalogue.getLogoUrl());
  }
}
