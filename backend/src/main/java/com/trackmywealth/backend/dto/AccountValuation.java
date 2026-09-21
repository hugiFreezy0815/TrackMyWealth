package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * @param valueKnown {@code false} when this account has no resolvable value: its type has no value
 *     source yet, or it has a source but no data (a {@code CUSTOM_ASSET} never valued, a credit
 *     card with no ledger history) - excluded from any total rather than counted as zero (PR-011)
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
    boolean valueKnown) {}
