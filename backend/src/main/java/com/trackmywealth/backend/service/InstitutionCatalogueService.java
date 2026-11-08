package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.InstitutionCatalogueEntrySummaryResponse;
import com.trackmywealth.backend.dto.PagingValues;
import com.trackmywealth.backend.entity.InstitutionCatalogueListingRow;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-04-01/US-04-10: search the shared institution catalogue (FR-INS-007), including each entry's
 * import formats. It reads only shared reference data and relies on authentication alone; linking
 * an entry to a portfolio is {@link PortfolioService}'s. The name and country filters apply in SQL
 * before counting and paging, and the formats are aggregated in the same page query.
 */
@Service
public class InstitutionCatalogueService {

  private static final Pattern COUNTRY_CODE = Pattern.compile("[A-Za-z]{2}");

  private final InstitutionCatalogueRepository institutionCatalogueRepository;

  public InstitutionCatalogueService(
      InstitutionCatalogueRepository institutionCatalogueRepository) {
    this.institutionCatalogueRepository = institutionCatalogueRepository;
  }

  @Transactional(readOnly = true)
  public Page<InstitutionCatalogueEntrySummaryResponse> searchCatalogue(
      @Nullable String query, @Nullable List<String> country, Pageable requested) {
    Pageable pageable = PagingValues.requireBounded(requested, "id");
    String countries = normalizeCountries(country);
    return institutionCatalogueRepository
        .searchCatalogue(containsPattern(query), countries, pageable)
        .map(InstitutionCatalogueService::toSummary);
  }

  /**
   * A case-insensitive "contains" pattern in which {@code %}, {@code _} and the escape character
   * itself match only themselves, as the derived {@code ContainingIgnoreCase} query this replaced
   * did; {@code null} for a blank query.
   */
  static @Nullable String containsPattern(@Nullable String query) {
    if (query == null || query.isBlank()) {
      return null;
    }
    String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  /**
   * Accepts repeated values or comma-separated values, ignoring blanks. A two-letter unknown code
   * is valid and simply matches no catalogue entries (e.g. XX).
   */
  private static @Nullable String normalizeCountries(@Nullable List<String> requested) {
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
        if (!COUNTRY_CODE.matcher(code).matches()) {
          throw new ApiException(
              ProblemCode.VALIDATION_FAILED,
              "country must contain ISO 3166-1 alpha-2 country codes.");
        }
        countries.add(code.toUpperCase(Locale.ROOT));
      }
    }
    return countries.isEmpty() ? null : String.join(",", countries);
  }

  private static InstitutionCatalogueEntrySummaryResponse toSummary(
      InstitutionCatalogueListingRow catalogue) {
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
