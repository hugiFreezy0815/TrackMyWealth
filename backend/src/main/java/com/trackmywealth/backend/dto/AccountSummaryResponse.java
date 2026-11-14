package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An account as {@code /api/v1/accounts} returns it.
 *
 * @param warnings the account's data-quality warnings ({@link DataQualityWarningValues}), empty
 *     when there are none (PR-011, FR-CON-007)
 * @param reconciliation the account's current cash reconciliation signal (US-25-02)
 * @param savingsPocketAccountId the account's savings pocket, an account of its own that an import
 *     created for the bank's pocket moves (US-07-22); {@code null} while it has none
 */
public record AccountSummaryResponse(
    UUID id,
    UUID portfolioId,
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
    @Nullable UUID savingsPocketAccountId,
    String status,
    @Nullable OffsetDateTime archivedAt,
    @Nullable LocalDate openedAt,
    @Nullable LocalDate closedAt,
    @Nullable String jurisdiction,
    int version,
    List<String> warnings,
    ReconciliationStatusResponse reconciliation) {

  public AccountSummaryResponse {
    // Defensive/immutable copy (SpotBugs EI_EXPOSE_REP).
    warnings = List.copyOf(warnings);
  }
}
