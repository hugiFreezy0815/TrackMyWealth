package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response for {@code GET /api/v1/net-worth} (US-09-01) - a deliberately <b>partial</b> net worth:
 * it covers only the accounts that have a value source today (see {@code AccountValuationService}),
 * which is why {@link #complete} exists. US-11-01 supersedes it with the full consolidated figure.
 *
 * @param reportingCurrency the requesting user's own {@code reporting_currency} (FR-USR-007) -
 *     every figure below is expressed in it
 * @param netWorth {@code totalAssets - totalLiabilities} - may be negative, never suppressed or
 *     clamped to zero. Liabilities are subtracted, never added (FR-CC-003, DM-12)
 * @param complete {@code false} when at least one {@link #accounts} entry has {@code valueKnown =
 *     false} - the totals only ever sum the accounts that do, so an incomplete figure is visible on
 *     inspection (PR-011) rather than silently understated
 * @param approximate {@code true} when at least one included account's value is an approximation or
 *     an assumption rather than a measured figure - a loan or mortgage counted at its original
 *     principal, a card with nothing recorded yet counted as owing zero (see {@link
 *     ValueBasisValues#isApproximate}). Independent of {@link #complete}: an approximate figure is
 *     still a known one, but must not be presented as exact
 * @param warnings every data-quality warning ({@link DataQualityWarningValues}) carried by at least
 *     one of {@link #accounts}, each once and sorted - the headline must show them, not only the
 *     account they come from (PR-011, FR-CON-007)
 * @param accounts every active account the caller may see (at least {@code BALANCE_ONLY}), present
 *     even when its value is unknown so a caller can see which accounts are excluded from the
 *     totals
 */
public record NetWorthResponse(
    String reportingCurrency,
    LocalDate asOf,
    BigDecimal totalAssets,
    BigDecimal totalLiabilities,
    BigDecimal netWorth,
    boolean complete,
    boolean approximate,
    List<String> warnings,
    List<AccountValuation> accounts) {

  public NetWorthResponse {
    // Defensive/immutable copy (SpotBugs EI_EXPOSE_REP), same as InstitutionSummaryResponse.
    warnings = List.copyOf(warnings);
    accounts = List.copyOf(accounts);
  }
}
