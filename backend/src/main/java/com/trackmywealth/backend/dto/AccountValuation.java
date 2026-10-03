package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One account's current value, expressed in {@code currency} - the DM-17 uniform valuation result
 * every consumer of "what is this account worth" (institution summary, account balance, net worth)
 * shares.
 *
 * <p>{@code value} is always a <b>non-negative-by-convention magnitude</b> read together with
 * {@code nature}: for an {@code ASSET} it is what is held, for a {@code LIABILITY} it is what is
 * owed (a credit card with CHF 85.00 outstanding has {@code value = 85.00}, {@code nature =
 * LIABILITY}). Aggregations subtract liabilities from assets; nothing hand-rolls a sign from the
 * account type (US-11-01, DM-12). A liability can be negative only when it is overpaid (a card in
 * credit).
 *
 * @param nativeCurrency the currency {@code value} is actually denominated in before any conversion
 *     to {@code currency} - {@code account.native_currency} for every account type except {@code
 *     CREDIT_CARD}, where it is the card's {@code billing_currency} instead (US-09-04/FR-CC-010):
 *     the two may legitimately differ, and a card's ledger/balance is always in billing terms,
 *     never the account's own reporting-currency label
 * @param value {@code null} when {@code valueKnown} is {@code false}
 * @param conversionRate the rate applied to convert {@code nativeCurrency} to {@code currency}
 *     (FR-INS-SUM-004) - {@code null} when no conversion was needed or when {@code valueKnown} is
 *     {@code false}
 * @param conversionRateDate the date the conversion was requested for (today - FR-CUR-011's
 *     valuation-date convention, see {@code docs/architecture/calculation-methodology.md}), not
 *     necessarily the date the underlying stored rate is itself dated to - see {@code
 *     conversionRateCarriedForward} for that. {@code null} under the same conditions as {@code
 *     conversionRate}
 * @param conversionRateCarriedForward {@code true} when the rate used was carried forward from an
 *     earlier date rather than resolved exactly for {@code conversionRateDate} (FR-CUR-012/PR-011)
 *     - always {@code false} when {@code conversionRate} is {@code null}
 * @param conversionRateStale {@code true} when that earlier rate is older than {@code
 *     app.fx.stale-after} - more than a weekend or holiday explains, e.g. the rate provider has
 *     been unavailable (NFR-CON-003/PR-011, #223). Always {@code false} when {@code
 *     conversionRateCarriedForward} is {@code false}
 * @param valueKnown {@code false} when this account has no resolvable value: its type has no value
 *     source yet, or it has a source but no data (a {@code CUSTOM_ASSET} never valued) - excluded
 *     from any total rather than counted as zero (PR-011)
 * @param valueBasis where {@code value} came from ({@link ValueBasisValues}) - {@code null} when
 *     {@code valueKnown} is {@code false}. A known value is not necessarily an exact one: {@code
 *     LEDGER_EMPTY} (an assumed zero) and {@code ORIGINAL_PRINCIPAL} (not the current outstanding
 *     balance) are approximations a client should surface as such, see {@link
 *     ValueBasisValues#isApproximate}
 * @param warnings the account's data-quality warnings ({@link DataQualityWarningValues}), empty
 *     when there are none - independent of {@code valueKnown}, a client shows them on the account
 *     (PR-011, FR-CON-007)
 */
public record AccountValuation(
    UUID accountId,
    String name,
    String nature,
    String nativeCurrency,
    String currency,
    BigDecimal value,
    BigDecimal conversionRate,
    LocalDate conversionRateDate,
    boolean conversionRateCarriedForward,
    boolean conversionRateStale,
    boolean valueKnown,
    String valueBasis,
    List<String> warnings) {

  public AccountValuation {
    // Defensive/immutable copy (SpotBugs EI_EXPOSE_REP), same as NetWorthResponse.
    warnings = List.copyOf(warnings);
  }
}
