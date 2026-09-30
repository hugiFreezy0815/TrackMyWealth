package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response for {@code GET /api/v1/admin/reference-data} (US-01-04, FR-REF-001/010): the reference
 * package currently loaded and what it contains, so an administrator can judge whether category and
 * institution suggestions are current.
 *
 * @param packageVersion e.g. {@code 1.1.0-baseline}
 * @param publicationDate when the package's content was published, not when this installation
 *     loaded it
 * @param importedAt when this installation loaded it
 * @param importedBy the user who imported it; {@code null} for a baseline shipped with the
 *     application
 * @param contents how much of each kind of reference data is loaded
 * @param stalenessWarnings effective-dated values with no entry for the current period (FR-REF-011)
 *     - never the baseline's mere age. Empty until effective-dated reference data (e.g. pension
 *     contribution limits, EPIC 32) exists. Structured, not free text, so a client can show and
 *     translate each one without parsing it.
 */
public record ReferenceDataResponse(
    String packageVersion,
    LocalDate publicationDate,
    OffsetDateTime importedAt,
    UUID importedBy,
    Contents contents,
    List<StalenessWarning> stalenessWarnings) {

  public ReferenceDataResponse {
    stalenessWarnings = List.copyOf(stalenessWarnings);
  }

  /**
   * Counts of the loaded reference data, and the GICS structure version in force ({@code null} if
   * none is).
   */
  public record Contents(
      long catalogueInstitutions,
      long defaultCategories,
      long sourceCodeMappings,
      long fallbackSectors,
      String gicsStructureVersion) {}

  /**
   * One effective-dated reference value with no entry for the current period (FR-REF-011).
   *
   * @param code what is missing, as a stable key a client translates, e.g. {@code
   *     PENSION_CONTRIBUTION_LIMIT_MISSING}
   * @param subject what it is missing for, e.g. the pension scheme {@code CH_PILLAR_3A}
   * @param missingPeriod the period with no value, e.g. {@code 2027}
   */
  public record StalenessWarning(String code, String subject, String missingPeriod) {}
}
