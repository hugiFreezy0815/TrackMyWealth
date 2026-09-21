package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response for {@code GET /api/v1/cash-flow} (US-09-02) - a deliberately <b>partial</b> spending
 * view for one calendar month, enough to show that a card settlement is never counted a second time
 * (FR-CC-005/RULE-009). EPIC 10 supersedes it with the full income/expense/savings cash flow.
 *
 * <p>Spending is attributed to the transaction's booking date, never to when a card statement was
 * settled (FR-CC-009), and reported per currency: converting a flow to one reporting currency is a
 * realised-flow FX conversion (see {@code docs/architecture/calculation-methodology.md}) this view
 * does not attempt.
 *
 * @param month the calendar month, {@code yyyy-MM}
 * @param spending card purchases and withdrawals in the month, per currency, as positive amounts -
 *     internal transfers and unresolved settlements excluded
 * @param pendingReview payments that look like a card settlement but are not resolved yet, per
 *     currency, as positive amounts. They are neither in {@code spending} nor silently dropped:
 *     each needs a member's decision before it can count as spending or as a settlement (FR-CF-004)
 * @param complete {@code false} while anything is in {@code pendingReview} - {@code spending} may
 *     then be missing a payment that turns out to be a real expense
 */
public record CashFlowResponse(
    String month,
    List<CurrencyAmount> spending,
    List<CurrencyAmount> pendingReview,
    boolean complete) {

  public CashFlowResponse {
    // Defensive/immutable copies (SpotBugs EI_EXPOSE_REP), same as NetWorthResponse.
    spending = List.copyOf(spending);
    pendingReview = List.copyOf(pendingReview);
  }

  /** An amount in one currency. */
  public record CurrencyAmount(String currency, BigDecimal amount) {}
}
