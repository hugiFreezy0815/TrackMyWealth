package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response for {@code GET /api/v1/institutions/{institutionId}/summary} (US-04-03).
 *
 * @param containerCurrency the institution's own container currency, whatever the summary is shown
 *     in
 * @param currency the currency every figure below is in: {@code containerCurrency} by default, or
 *     the caller's ad hoc {@code currency} (US-06-05)
 * @param netValue {@code totalAssets - totalLiabilities} - may be negative (FR-INS-SUM-001), never
 *     suppressed or clamped to zero
 * @param complete {@code false} when at least one {@link #accounts} entry has {@code valueKnown =
 *     false} - {@code totalAssets}/{@code totalLiabilities}/{@code netValue} only ever sum the
 *     accounts that do, so an incomplete summary is visible on inspection (PR-011) rather than
 *     silently understated
 * @param accounts every currently-active account under the institution, drillable (FR-INS-SUM-002)
 *     - present even when {@code valueKnown} is {@code false}, so a caller can see which accounts
 *     are excluded from the totals, not just that the totals might be incomplete
 */
public record InstitutionSummaryResponse(
    UUID institutionId,
    String containerCurrency,
    String currency,
    BigDecimal totalAssets,
    BigDecimal totalLiabilities,
    BigDecimal netValue,
    boolean complete,
    List<AccountContribution> accounts) {

  public InstitutionSummaryResponse {
    // Defensive/immutable copy - InstitutionService builds this from its own freshly-created
    // ArrayList, but a record must never expose a caller-mutable reference to its own field
    // regardless of who happens to hold the only other reference today (SpotBugs EI_EXPOSE_REP).
    accounts = List.copyOf(accounts);
  }

  /**
   * @param value the account's value in the summary's {@code currency} - {@code null} when {@code
   *     valueKnown} is {@code false}
   * @param conversionRate the rate applied to convert {@code nativeCurrency} to the summary's
   *     {@code currency} (FR-INS-SUM-004) - {@code null} when no conversion was needed ({@code
   *     nativeCurrency} already equals it) or when {@code valueKnown} is {@code false}
   * @param conversionRateDate the date the conversion was requested for (today - FR-CUR-011's
   *     valuation-date convention, see {@code docs/architecture/calculation-methodology.md}), not
   *     necessarily the date the underlying stored rate is itself dated to - see {@code
   *     conversionRateCarriedForward} for that. {@code null} under the same conditions as {@code
   *     conversionRate}
   * @param conversionRateCarriedForward {@code true} when the rate used was carried forward from an
   *     earlier date rather than resolved exactly for {@code conversionRateDate} (FR-CUR-012/PR-011
   *     - {@code FxRateService.CurrencyConversionResult#carriedForward()}) - always {@code false}
   *     when {@code conversionRate} is {@code null}
   * @param conversionRateStale {@code true} when that earlier rate is older than {@code
   *     app.fx.stale-after} - more than a weekend or holiday explains, e.g. the rate provider has
   *     been unavailable (NFR-CON-003/PR-011, #223); the figure must be marked as such. Always
   *     {@code false} when {@code conversionRateCarriedForward} is {@code false}
   * @param valueKnown {@code false} when this account's type has no value source yet in this
   *     codebase (every type except {@code CUSTOM_ASSET}, {@code MORTGAGE} and {@code LOAN}, or a
   *     {@code CUSTOM_ASSET} account with no valuation recorded at all) - excluded from the
   *     institution's totals, not counted as zero
   */
  public record AccountContribution(
      UUID accountId,
      String name,
      String nature,
      String nativeCurrency,
      BigDecimal value,
      BigDecimal conversionRate,
      LocalDate conversionRateDate,
      boolean conversionRateCarriedForward,
      boolean conversionRateStale,
      boolean valueKnown) {}
}
