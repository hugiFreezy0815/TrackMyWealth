package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * US-10-02: how {@code LoanRepaymentService} splits one payment. {@code interest} and {@code
 * principal} are positive-or-zero magnitudes adding up to the payment, both {@code null} for an
 * {@link LoanRepaymentSplitValues#UNKNOWN} split.
 *
 * @param splitSource one of {@link LoanRepaymentSplitValues}
 * @param estimated the PR-011 flag: not exactly what a source states
 * @param scheduleEntryId the amortisation schedule entry used, for a {@code SCHEDULE} split
 */
public record LoanPaymentSplit(
    String splitSource,
    BigDecimal interest,
    BigDecimal principal,
    boolean estimated,
    UUID scheduleEntryId) {

  /** Whether interest and principal are known (estimated or not). */
  public boolean known() {
    return !LoanRepaymentSplitValues.UNKNOWN.equals(splitSource);
  }
}
