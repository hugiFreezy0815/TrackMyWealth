package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One loan or mortgage payment and its split (US-10-02, FR-CF-006).
 *
 * @param amount what was paid, a positive magnitude
 * @param interestAmount the interest part, recorded as an {@code EXPENSE} on the paying account;
 *     {@code null} while the split is unknown
 * @param principalAmount the principal part, recorded as a {@code DEBT_REPAYMENT} pair (a
 *     balance-sheet movement, never spending); {@code null} while the split is unknown
 * @param splitSource where the split came from, one of {@link LoanRepaymentSplitValues}
 * @param splitEstimated {@code true} when the split is not exactly what a source states - an
 *     estimate, a schedule entry whose instalment differs from the payment, or an unknown split
 *     (PR-011); a client must show it as such
 * @param amortisationScheduleEntryId the schedule entry used, for a {@code SCHEDULE} split
 * @param transactions the ledger rows the payment wrote that are still in effect or listed (a
 *     soft-deleted row is gone): the paying account's outflow and the loan's inflow of the
 *     principal, and the interest
 */
public record LoanRepaymentResponse(
    UUID id,
    UUID loanAccountId,
    UUID payingAccountId,
    LocalDate bookingDate,
    BigDecimal amount,
    String currency,
    BigDecimal interestAmount,
    BigDecimal principalAmount,
    String splitSource,
    boolean splitEstimated,
    UUID amortisationScheduleEntryId,
    String externalId,
    List<TransactionResponse> transactions,
    OffsetDateTime createdAt) {

  public LoanRepaymentResponse {
    // Defensive/immutable copy (SpotBugs EI_EXPOSE_REP), same as CashFlowResponse.
    transactions = List.copyOf(transactions);
  }
}
