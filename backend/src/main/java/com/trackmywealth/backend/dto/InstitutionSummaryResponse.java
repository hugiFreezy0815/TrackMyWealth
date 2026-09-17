package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response for {@code GET /api/v1/institutions/{id}/summary} (US-04-03, FR-INS-006/FR-INS-SUM-001
 * ..004).
 *
 * <p><b>Scoped to what this codebase can actually resolve today:</b> a per-account current value
 * only exists for {@code CUSTOM_ASSET} accounts ({@code custom_asset_valuation}, US-05-05) - no
 * transaction ledger, position, price or daily-valuation feature has landed yet (EPIC 07/12/14/15
 * /16), so every other account type has no source to compute a value from. A resolvable account
 * whose currency has no FX rate into the container currency yet is treated the same way (see {@code
 * InstitutionSummaryService}'s own Javadoc). Both cases still appear in {@link #accounts()}
 * (FR-INS-SUM-002's drill-down applies to every account in the container, not only the ones
 * currently priced) with {@code valueResolvable = false} - see {@link
 * AccountLine#valueResolvable()} for exactly which fields are still populated - and are excluded
 * from {@link #totalAssets()}/{@link #totalLiabilities()} rather than silently counted as zero
 * (PR-011, matching US-11-01's own documented edge case). {@link #hasUnresolvedValues()} surfaces
 * this at the headline level (FR-CON-007) whenever it happens.
 *
 * <p>Also does not attempt to surface "unreconciled" status (FR-CON-007's other named condition):
 * no reconciliation feature exists yet either (EPIC 25) - only the FX-staleness half of that
 * requirement, {@link #hasCarriedForwardFxRate()}, is meaningful today.
 *
 * @param totalAssets sum of every resolvable {@code ASSET}-nature account's converted value - a
 *     non-negative magnitude in {@code containerCurrency}
 * @param totalLiabilities sum of every resolvable {@code LIABILITY}-nature account's converted
 *     value - a non-negative magnitude in {@code containerCurrency} (not pre-negated; subtract it
 *     from {@code totalAssets} to get {@link #netValue()})
 * @param netValue {@code totalAssets - totalLiabilities}, signed - legitimately negative when a
 *     container's liabilities outweigh its assets (FR-INS-SUM-003/C6), never suppressed or clamped
 *     to zero
 * @param hasUnresolvedValues true if any contributing account's value could not be resolved (see
 *     class Javadoc) - callers must not present the totals as complete when this is true
 * @param hasCarriedForwardFxRate true if any currency conversion behind these totals used a
 *     carried-forward (non-exact-date) FX rate (FR-CUR-012/PR-011)
 */
public record InstitutionSummaryResponse(
    UUID institutionId,
    String containerCurrency,
    BigDecimal totalAssets,
    BigDecimal totalLiabilities,
    BigDecimal netValue,
    boolean hasUnresolvedValues,
    boolean hasCarriedForwardFxRate,
    List<AccountLine> accounts) {

  public InstitutionSummaryResponse {
    // Defensive/immutable copy - see spotbugs-exclude.xml's matching entry for why SpotBugs still
    // flags accounts() without this (it doesn't do the dataflow analysis to see the copy).
    accounts = List.copyOf(accounts);
  }

  /**
   * One contributing (or would-be contributing) account (FR-INS-SUM-002's drill-down).
   *
   * @param nativeValue the account's own current value in {@code nativeCurrency}; {@code null} only
   *     when no value is known for this account at all (see {@code valueResolvable}) - still
   *     populated even when {@code valueResolvable} is false because the value is known but not
   *     currently convertible to the container currency (no FX rate exists for the pair yet)
   * @param convertedValue {@code nativeValue} converted to the summary's container currency; {@code
   *     null} whenever {@code valueResolvable} is false, including the known-but-unconvertible case
   *     above
   * @param fxRateUsed the rate applied to convert {@code nativeValue}; {@code null} when {@code
   *     nativeCurrency} already equals the container currency (no conversion was needed, so there
   *     is no rate to inspect) or {@code convertedValue} is null
   * @param fxRateDate the date {@code fxRateUsed} actually carries (FR-INS-SUM-004) - null under
   *     the same conditions as {@code fxRateUsed}
   * @param carriedForward true when {@code fxRateUsed} was carried forward rather than exact for
   *     today (FR-CUR-012/PR-011); always false when no conversion was needed
   * @param valueResolvable true only when {@code convertedValue} is present - i.e. this account has
   *     a known native value AND a usable rate into the container currency. False when: the
   *     account's type has no implemented value source yet (see class Javadoc), it does but no
   *     valuation has been recorded for it, or a value is known but no FX rate exists yet for its
   *     currency pair into the container currency.
   */
  public record AccountLine(
      UUID accountId,
      String name,
      String nature,
      String nativeCurrency,
      BigDecimal nativeValue,
      BigDecimal convertedValue,
      BigDecimal fxRateUsed,
      LocalDate fxRateDate,
      boolean carriedForward,
      boolean valueResolvable) {}
}
