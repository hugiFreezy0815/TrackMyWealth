package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.InstitutionCatalogueEntrySummaryResponse;
import com.trackmywealth.backend.dto.PagingValues;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository.CatalogueListing;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-04-01/US-04-10: search the shared catalogue, including import formats. Country filtering
 * happens in SQL before counting and paging; templates are aggregated in the same page query.
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
      String query, List<String> country, Pageable requested) {
    Pageable pageable = PagingValues.requireBounded(requested, "id");
    String countries = normalizeCountries(country);
    return institutionCatalogueRepository
        .searchCatalogue(query == null || query.isBlank() ? null : query, countries, pageable)
        .map(InstitutionCatalogueService::toSummary);
  }

  /**
   * Accepts repeated values or comma-separated values, ignoring blanks. A two-letter unknown code
   * is valid and simply matches no catalogue entries (e.g. XX).
   */
  private static String normalizeCountries(List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      return null;
    }
    Set<String> countries = new TreeSet<>();
    for (String value : requested) {
      for (String part : value.split(",", -1)) {
        String code = part.strip();
        if (code.isEmpty()) {
          continue;
        }
        if (!code.matches("[A-Za-z]{2}")) {
          throw new ApiException(
              ProblemCode.VALIDATION_FAILED,
              "country must contain ISO 3166-1 alpha-2 country codes.");
        }
        countries.add(code.toUpperCase(Locale.ROOT));
      }
    }
    return countries.isEmpty() ? null : String.join(",", countries);
  }

  private static InstitutionCatalogueEntrySummaryResponse toSummary(CatalogueListing catalogue) {
    String formats = catalogue.getImportFormats();
    return new InstitutionCatalogueEntrySummaryResponse(
        catalogue.getId(),
        catalogue.getName(),
        catalogue.getCountry(),
        catalogue.getInstitutionType(),
        catalogue.getIdentifier(),
        catalogue.getLogoUrl(),
        formats.isEmpty() ? List.of() : List.of(formats.split(",")));
  }
}
