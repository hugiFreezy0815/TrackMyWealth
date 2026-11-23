package com.trackmywealth.backend.dto;

import java.util.List;
import java.util.UUID;

/**
 * #693: a loan's or mortgage's payments summed per calendar year, newest year first, so a client
 * shows "interest this year" without adding up repayments itself. Only years with a payment are
 * listed. Amounts are in {@code currency}, the loan's own, as every repayment is.
 */
public record LoanRepaymentYearlyTotalsResponse(
    UUID loanAccountId, String currency, List<LoanRepaymentYearTotalResponse> years) {

  public LoanRepaymentYearlyTotalsResponse {
    // Defensive/immutable copy (SpotBugs EI_EXPOSE_REP), same as LoanRepaymentResponse.
    years = List.copyOf(years);
  }
}
