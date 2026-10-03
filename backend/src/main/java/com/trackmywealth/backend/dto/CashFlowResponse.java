package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response for {@code GET /api/v1/cash-flow} (US-10-01, US-06-05): one calendar month's cash flow,
 * by booking date. See {@code CashFlowService} for what each figure holds; together they never
 * count a movement twice. Without a {@code currency} parameter, each figure is a list with one
 * entry per original transaction currency. With one, each figure is a single entry in that
 * currency, every booking date converted at its own rate (the realised-flow rule in {@code
 * docs/architecture/calculation-methodology.md}); an empty figure is an empty list either way.
 *
 * @param month the calendar month, {@code yyyy-MM}
 * @param income income, interest and dividends, as a signed sum
 * @param spending consumption - purchases, withdrawals, expenses, fees and tax - as positive
 *     amounts
 * @param saving money moved into accounts that count as saving, less money moved back out
 * @param pendingReview what awaits a member's decision - unresolved card settlements, proposed
 *     transfer pairs, unlinked transfer legs - as positive amounts; in no other figure, and never
 *     silently dropped (FR-CF-004/005)
 * @param complete {@code false} while anything is in {@code pendingReview}, or while any converted
 *     figure is unknown ({@code valueKnown = false}, PR-011)
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

  /**
   * One cash-flow figure in one currency.
   *
   * <p>A converted figure combines up to a month of daily rates, so it carries no single rate or
   * rate date - only whether any day's rate was carried forward or stale (decision on #224). An
   * unconverted figure is always known, with both marks {@code false}.
   *
   * @param amount {@code null} when {@code valueKnown} is {@code false}
   * @param valueKnown {@code false} when no rate resolves for at least one contributing booking
   *     date; the figure is then unknown, never zero or 1:1 (PR-011)
   * @param conversionRateCarriedForward {@code true} when any contributing day's rate was carried
   *     forward from an earlier date (FR-CUR-012/PR-011)
   * @param conversionRateStale {@code true} when any contributing day's carried-forward rate is
   *     older than the staleness limit
   */
  public record CurrencyAmount(
      String currency,
      BigDecimal amount,
      boolean valueKnown,
      boolean conversionRateCarriedForward,
      boolean conversionRateStale) {

    /** An unconverted amount in its own currency. */
    public CurrencyAmount(String currency, BigDecimal amount) {
      this(currency, amount, true, false, false);
    }
  }
}
