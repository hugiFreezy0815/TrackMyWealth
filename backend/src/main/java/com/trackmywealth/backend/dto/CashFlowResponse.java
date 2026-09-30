package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response for {@code GET /api/v1/cash-flow} (US-10-01): one calendar month's cash flow per
 * currency, by booking date. See {@code CashFlowService} for what each figure holds; together they
 * never count a movement twice. Converting to one reporting currency is a realised-flow FX
 * conversion (see {@code docs/architecture/calculation-methodology.md}) this view does not attempt.
 *
 * @param month the calendar month, {@code yyyy-MM}
 * @param income income, interest and dividends, as a signed sum per currency
 * @param spending consumption - purchases, withdrawals, expenses, fees and tax - as positive
 *     amounts
 * @param saving money moved into accounts that count as saving, less money moved back out
 * @param pendingReview what awaits a member's decision - unresolved card settlements, proposed
 *     transfer pairs, unlinked transfer legs - as positive amounts; in no other figure, and never
 *     silently dropped (FR-CF-004/005)
 * @param complete {@code false} while anything is in {@code pendingReview}
 */
public record CashFlowResponse(
    String month,
    List<CurrencyAmount> income,
    List<CurrencyAmount> spending,
    List<CurrencyAmount> saving,
    List<CurrencyAmount> pendingReview,
    boolean complete) {

  public CashFlowResponse {
    // Defensive/immutable copies (SpotBugs EI_EXPOSE_REP), same as NetWorthResponse.
    income = List.copyOf(income);
    spending = List.copyOf(spending);
    saving = List.copyOf(saving);
    pendingReview = List.copyOf(pendingReview);
  }

  /** An amount in one currency. */
  public record CurrencyAmount(String currency, BigDecimal amount) {}
}
