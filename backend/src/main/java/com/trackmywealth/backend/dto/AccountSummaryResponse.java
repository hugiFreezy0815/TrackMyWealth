package com.trackmywealth.backend.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An account as {@code /api/v1/accounts} returns it.
 *
 * @param warnings the account's data-quality warnings ({@link DataQualityWarningValues}), empty
 *     when there are none (PR-011, FR-CON-007)
 */
public record AccountSummaryResponse(
    UUID id,
    UUID financialInstitutionId,
    String name,
    String accountType,
    String nativeCurrency,
    String nature,
    boolean holdsPositions,
    boolean hasTransactions,
    boolean hasStatementCycle,
    boolean hasAmortisation,
    boolean hasContributionLimit,
    boolean discretionary,
    boolean manualValuation,
    boolean countsAsSaving,
    String status,
    OffsetDateTime archivedAt,
    int version,
    List<String> warnings) {

  public AccountSummaryResponse {
    // Defensive/immutable copy (SpotBugs EI_EXPOSE_REP).
    warnings = List.copyOf(warnings);
  }
}
