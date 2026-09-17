package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.FxRateLookupResult;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-06-01: read contract for {@code fx_rate} - the FX equivalent of {@code price} (FR-PRC-013).
 * Storage itself is plain {@link FxRateRepository#save}, with no service-layer wrapper: nothing
 * writes a rate yet outside of tests (EPIC 30's scheduled fetch job, which will, is not built - see
 * the story's own scoping note), so there is nothing for a write method to do beyond what {@code
 * JpaRepository} already provides.
 *
 * <p>US-06-02/FR-CUR-010: {@link #getConversionRate} and {@link #convert} always prefer a direct
 * {@code baseCurrency}/{@code quoteCurrency} pair when one exists, and only fall back to chaining
 * through {@value #INTERMEDIATE_CURRENCY} (this codebase's documented common intermediate - the de
 * facto global FX hub currency most providers quote everything against, so a {@code
 * baseCurrency}/{@value #INTERMEDIATE_CURRENCY} or {@value #INTERMEDIATE_CURRENCY}/{@code
 * quoteCurrency} leg is the most likely to actually exist when the direct pair doesn't) when no
 * direct pair is stored at all. Deliberately does not attempt inversion (e.g. answering a CHF/USD
 * query from a stored USD/CHF row via reciprocal): neither AC in that story requires it, and
 * reciprocal division would need its own rounding-policy decision this story doesn't otherwise need
 * to make - left for whichever future story actually needs it.
 *
 * <p>No controller yet - nothing consumes either read contract outside of tests this sprint (EPIC
 * 15/16/18/19, which would, are not built yet).
 */
@Service
public class FxRateService {

  // FR-CUR-010's fallback (not its primary rule - the primary rule is direct-pair-first; this is
  // only reached when no direct pair is stored at all). Not configurable: a fixed, documented
  // choice is the entire point of "documented, explicit chain" in the story's own AC wording -
  // a configurable intermediate would just move the ambiguity this story exists to remove.
  private static final String INTERMEDIATE_CURRENCY = "USD";

  // NFR-CALC-007: a single documented rounding policy, applied once at presentation - never
  // accumulated through intermediate steps (getConversionRate below multiplies two full-precision
  // fx_rate.rate values, NUMERIC(20,10), with no rounding in between; only convert() rounds, and
  // only the final money amount, never the rate itself). Matches this codebase's existing
  // NUMERIC(20,4) money-storage convention (see CLAUDE.md).
  private static final int MONEY_SCALE = 4;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

  private final FxRateRepository fxRateRepository;

  public FxRateService(FxRateRepository fxRateRepository) {
    this.fxRateRepository = fxRateRepository;
  }

  /**
   * The rate for {@code baseCurrency}/{@code quoteCurrency} effective on {@code date} from {@code
   * source} - the stored rate for that exact date if one exists, otherwise the most recent prior
   * one, marked {@link FxRateLookupResult#carriedForward} (FR-CUR-012).
   *
   * @throws ResponseStatusException 400 if any argument is missing or {@code baseCurrency}/{@code
   *     quoteCurrency} isn't a valid ISO 4217 code (same check as {@code @ValidCurrencyCode} uses
   *     elsewhere in this codebase) - callers must not be able to mistake a rejected request for a
   *     404's "nothing stored for this pair" (a lowercase or malformed code would otherwise just
   *     never match any stored row)
   * @throws ResponseStatusException 404 if no rate exists for this pair/source on or before {@code
   *     date} at all (PR-011: refuse rather than silently default to 1.0)
   */
  @Transactional(readOnly = true)
  public FxRateLookupResult getRate(
      String baseCurrency, String quoteCurrency, LocalDate date, String source) {
    requireValidCurrencyCode(baseCurrency, "baseCurrency");
    requireValidCurrencyCode(quoteCurrency, "quoteCurrency");
    requireNonNull(date, "date");
    requireNonBlank(source, "source");

    FxRate fxRate =
        findOnOrBefore(baseCurrency, quoteCurrency, source, date)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No FX rate available for "
                            + baseCurrency
                            + "/"
                            + quoteCurrency
                            + " from '"
                            + source
                            + "' on or before "
                            + date
                            + "."));

    return new FxRateLookupResult(fxRate.getRate(), date, fxRate.getRateDate(), fxRate.getSource());
  }

  /**
   * US-06-02/FR-CUR-010: the effective rate for converting {@code baseCurrency} to {@code
   * quoteCurrency} on {@code date}, always preferring a direct stored (or carried-forward) pair,
   * and falling back to chaining through {@value #INTERMEDIATE_CURRENCY} only when no direct pair
   * is stored at all for either the exact date or any prior one - see the class Javadoc for why
   * that specific fallback currency, and why no other fallback (e.g. inversion) is attempted.
   *
   * @throws ResponseStatusException 400, same as {@link #getRate}
   * @throws ResponseStatusException 404 if neither a direct pair nor a complete chain through
   *     {@value #INTERMEDIATE_CURRENCY} exists (PR-011: refuse rather than silently default to 1.0,
   *     in any chain)
   */
  @Transactional(readOnly = true)
  public CurrencyConversionResult getConversionRate(
      String baseCurrency, String quoteCurrency, LocalDate date, String source) {
    requireValidCurrencyCode(baseCurrency, "baseCurrency");
    requireValidCurrencyCode(quoteCurrency, "quoteCurrency");
    requireNonNull(date, "date");
    requireNonBlank(source, "source");

    // Converting a currency to itself is always rate 1.0, by definition - checked before any rate
    // lookup at all (not just before the chain fallback below), since neither a direct pair nor a
    // chain is a correct way to answer this: a same-currency pair with no direct row stored would
    // otherwise either 404 (when that currency is INTERMEDIATE_CURRENCY itself, e.g. USD/USD - the
    // very next check below would catch it, but by throwing, not by succeeding) or, worse, silently
    // chain through INTERMEDIATE_CURRENCY and return whatever rate(base,USD) * rate(USD,base)
    // happens to multiply to - correct only if those two independently-sourced rates are exact
    // reciprocals, which real FX data has no reason to be (found by review on this PR).
    if (baseCurrency.equals(quoteCurrency)) {
      return new CurrencyConversionResult(
          BigDecimal.ONE, baseCurrency, quoteCurrency, date, true, null, false, date);
    }

    Optional<FxRate> direct = findOnOrBefore(baseCurrency, quoteCurrency, source, date);
    if (direct.isPresent()) {
      FxRate fxRate = direct.get();
      return new CurrencyConversionResult(
          fxRate.getRate(),
          baseCurrency,
          quoteCurrency,
          date,
          true,
          null,
          !fxRate.getRateDate().equals(date),
          fxRate.getRateDate());
    }

    // One side is already the fallback intermediate - there is no third currency left to chain
    // through, so a missing direct pair here can never be recovered by chaining via itself.
    if (INTERMEDIATE_CURRENCY.equals(baseCurrency) || INTERMEDIATE_CURRENCY.equals(quoteCurrency)) {
      throw noConversionRateAvailable(baseCurrency, quoteCurrency, source, date);
    }

    FxRate firstLeg =
        findOnOrBefore(baseCurrency, INTERMEDIATE_CURRENCY, source, date)
            .orElseThrow(
                () -> noConversionRateAvailable(baseCurrency, quoteCurrency, source, date));
    FxRate secondLeg =
        findOnOrBefore(INTERMEDIATE_CURRENCY, quoteCurrency, source, date)
            .orElseThrow(
                () -> noConversionRateAvailable(baseCurrency, quoteCurrency, source, date));

    BigDecimal chainedRate = firstLeg.getRate().multiply(secondLeg.getRate());
    boolean carriedForward =
        !firstLeg.getRateDate().equals(date) || !secondLeg.getRateDate().equals(date);
    LocalDate chainRateDate =
        firstLeg.getRateDate().isBefore(secondLeg.getRateDate())
            ? firstLeg.getRateDate()
            : secondLeg.getRateDate();
    return new CurrencyConversionResult(
        chainedRate,
        baseCurrency,
        quoteCurrency,
        date,
        false,
        INTERMEDIATE_CURRENCY,
        carriedForward,
        chainRateDate);
  }

  /**
   * {@code amount} in {@code baseCurrency} converted to {@code quoteCurrency}, using {@link
   * #getConversionRate}'s direct-pair-first resolution, rounded per this class's documented
   * NFR-CALC-007 money policy - applied once, here, never accumulated through the rate resolution
   * itself. A caller that needs to know whether the underlying figure should be visibly marked as
   * carried-forward or chained (PR-011) calls {@link #getConversionRate} for that metadata
   * alongside this - no consumer needs both together yet, so this deliberately isn't bundled into
   * one richer return type (see class Javadoc: no controller/consumer exists this sprint).
   */
  @Transactional(readOnly = true)
  public BigDecimal convert(
      BigDecimal amount, String baseCurrency, String quoteCurrency, LocalDate date, String source) {
    requireNonNull(amount, "amount");
    CurrencyConversionResult conversion =
        getConversionRate(baseCurrency, quoteCurrency, date, source);
    return amount.multiply(conversion.rate()).setScale(MONEY_SCALE, MONEY_ROUNDING);
  }

  private Optional<FxRate> findOnOrBefore(
      String baseCurrency, String quoteCurrency, String source, LocalDate date) {
    return fxRateRepository
        .findFirstByBaseCurrencyAndQuoteCurrencyAndSourceAndRateDateLessThanEqualOrderByRateDateDesc(
            baseCurrency, quoteCurrency, source, date);
  }

  private static ResponseStatusException noConversionRateAvailable(
      String baseCurrency, String quoteCurrency, String source, LocalDate date) {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND,
        "No FX rate available for "
            + baseCurrency
            + "/"
            + quoteCurrency
            + " from '"
            + source
            + "' on or before "
            + date
            + ", directly or via "
            + INTERMEDIATE_CURRENCY
            + ".");
  }

  // Mirrors CurrencyCodeValidator's own check (java.util.Currency.getInstance, which is
  // case-sensitive/uppercase-only) rather than depending on jakarta.validation here: this is a
  // plain service method, not a request DTO field @ValidCurrencyCode can annotate.
  private static void requireValidCurrencyCode(String currencyCode, String fieldName) {
    requireNonNull(currencyCode, fieldName);
    try {
      Currency.getInstance(currencyCode);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          fieldName + " '" + currencyCode + "' is not a valid ISO 4217 currency code.",
          e);
    }
  }

  private static void requireNonNull(Object value, String fieldName) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required.");
    }
  }

  private static void requireNonBlank(String value, String fieldName) {
    requireNonNull(value, fieldName);
    if (value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required.");
    }
  }
}
