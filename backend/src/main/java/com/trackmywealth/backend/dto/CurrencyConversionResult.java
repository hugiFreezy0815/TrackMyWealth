package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result of {@code FxRateService.getConversionRate} (US-06-02/FR-CUR-010).
 *
 * @param rate multiply an amount in {@code baseCurrency} by this to get the {@code quoteCurrency}
 *     amount - full FX precision (matches {@code fx_rate.rate}'s {@code NUMERIC(20,10)}), not yet
 *     rounded to a money scale (NFR-CALC-007: rounding applies at presentation, never accumulated
 *     through intermediate steps - see {@code FxRateService#convert})
 * @param direct true when a single stored (or carried-forward) pair satisfied the request, either
 *     as stored or inverted (the ECB publishes {@code EUR/CHF}, never {@code CHF/EUR}); false when
 *     no such pair existed and the rate was derived by chaining through {@code
 *     intermediateCurrency} instead (FR-CUR-010's fallback, not its primary rule - the primary rule
 *     is that this chaining is what direct-pair resolution exists to avoid whenever a direct pair
 *     is available)
 * @param intermediateCurrency the currency chained through when {@code direct} is false; {@code
 *     null} when {@code direct} is true
 * @param carriedForward true when any leg of the resolution (the direct pair, or either leg of the
 *     chain) used a carried-forward rate rather than an exact match for {@code requestedDate}
 *     (FR-CUR-012/PR-011) - callers must mark any figure derived from this the same way they would
 *     for {@link FxRateLookupResult#carriedForward()}
 * @param stale true when any leg's stored rate is older than {@code app.fx.stale-after} before
 *     {@code requestedDate} - more than a weekend or a holiday explains, typically because the
 *     provider has been unavailable (NFR-CON-003, #223). Implies {@code carriedForward}; marked the
 *     same way wherever the figure is shown (PR-011)
 */
public record CurrencyConversionResult(
    BigDecimal rate,
    String baseCurrency,
    String quoteCurrency,
    LocalDate requestedDate,
    boolean direct,
    String intermediateCurrency,
    boolean carriedForward,
    boolean stale) {}
