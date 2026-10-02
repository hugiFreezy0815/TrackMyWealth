package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.FxRateLookupResult;
import com.trackmywealth.backend.dto.ResolvedFxRate;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.Currency;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-06-01: read contract for {@code fx_rate} - the FX equivalent of {@code price} (FR-PRC-013).
 * Rates are written by {@link FxRateImportService} (US-06-04, #223), which loads the ECB's daily
 * euro reference rates, or entered by hand; this class only reads them.
 *
 * <p>US-06-02/FR-CUR-010 and #223: {@link #getConversionRate} answers any pair of currencies from
 * the stored rates of one source, in this order:
 *
 * <ol>
 *   <li>the stored {@code baseCurrency}/{@code quoteCurrency} pair;
 *   <li>the stored reverse pair, inverted - the ECB publishes {@code EUR/CHF}, never {@code
 *       CHF/EUR}, and a currency's value in another is the same fact whichever way round it is
 *       quoted;
 *   <li>a chain through {@value #INTERMEDIATE_CURRENCY}, each leg found as in 1 or 2. The euro,
 *       because the default source (the ECB) quotes every currency against it: with nothing but its
 *       rates stored, every pair of published currencies resolves - {@code USD/CHF} as {@code
 *       USD/EUR} times {@code EUR/CHF}. A direct pair (1 or 2) always wins over the chain, so a
 *       hand-entered {@code USD/CHF} rate is used as entered.
 * </ol>
 *
 * <p>When nothing resolves and {@code source} is the import's own, {@link
 * FxRateImportService#fetchMissingHistory} loads the rates around a date that lies before every
 * stored one, and the lookup is repeated once (#223 fetch-on-missing).
 *
 * <p>Every result says whether a rate older than the requested date was carried forward
 * (FR-CUR-012) and whether that rate is older than {@code app.fx.stale-after} - stale, the mark a
 * provider outage leaves once it outlasts a weekend (NFR-CON-003, PR-011).
 */
@Service
public class FxRateService {

  // FR-CUR-010's fallback hub, see the class Javadoc. Not configurable: a fixed, documented choice
  // is the point of "documented, explicit chain" in US-06-02's AC.
  private static final String INTERMEDIATE_CURRENCY = "EUR";

  // NFR-CALC-007: a single documented rounding policy, applied once at presentation - never
  // accumulated through intermediate steps. A derived rate (an inverted pair, or a chain) is
  // computed at DECIMAL128 precision and rounded once, at the end, to fx_rate.rate's own
  // NUMERIC(20,10), so every rate this class returns can be stored and shown like a stored one.
  // convert() rounds the final money amount to NUMERIC(20,4), the money-storage convention.
  private static final int RATE_SCALE = 10;
  private static final int MONEY_SCALE = 4;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

  private final FxRateRepository fxRateRepository;
  private final FxRateImportService fxRateImportService;
  private final Period staleAfter;

  public FxRateService(
      FxRateRepository fxRateRepository,
      FxRateImportService fxRateImportService,
      @Value("${app.fx.stale-after:P5D}") Period staleAfter) {
    this.fxRateRepository = fxRateRepository;
    this.fxRateImportService = fxRateImportService;
    this.staleAfter = staleAfter;
  }

  /**
   * The stored rate for {@code baseCurrency}/{@code quoteCurrency} effective on {@code date} from
   * {@code source} - the stored rate for that exact date if one exists, otherwise the most recent
   * prior one, marked {@link FxRateLookupResult#carriedForward} (FR-CUR-012). Only ever the pair as
   * stored: no inversion, no chain - that is {@link #getConversionRate}.
   *
   * @throws ResponseStatusException 400 if any argument is missing or {@code baseCurrency}/{@code
   *     quoteCurrency} isn't a valid ISO 4217 code (same check as {@code @ValidCurrencyCode} uses
   *     elsewhere in this codebase) - callers must not be able to mistake a rejected request for a
   *     404's "nothing stored for this pair" (a lowercase or malformed code would otherwise just
   *     never match any stored row)
   * @throws ResponseStatusException 404 if no rate exists for this pair/source on or before {@code
   *     date} at all, even after fetch-on-missing (PR-011: refuse rather than silently default to
   *     1.0)
   */
  @Transactional(readOnly = true)
  public FxRateLookupResult getRate(
      String baseCurrency, String quoteCurrency, LocalDate date, String source) {
    requireValidCurrencyCode(baseCurrency, "baseCurrency");
    requireValidCurrencyCode(quoteCurrency, "quoteCurrency");
    requireNonNull(date, "date");
    requireNonBlank(source, "source");

    Optional<FxRate> stored = findOnOrBefore(baseCurrency, quoteCurrency, source, date);
    if (stored.isEmpty() && fetchedMissingHistory(source, date)) {
      stored = findOnOrBefore(baseCurrency, quoteCurrency, source, date);
    }
    FxRate fxRate =
        stored.orElseThrow(
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

    return new FxRateLookupResult(
        fxRate.getRate(),
        date,
        fxRate.getRateDate(),
        fxRate.getSource(),
        isStale(fxRate.getRateDate(), date));
  }

  /**
   * US-06-02/FR-CUR-010: the effective rate for converting {@code baseCurrency} to {@code
   * quoteCurrency} on {@code date}, resolved in the order the class Javadoc lists.
   *
   * @throws ResponseStatusException 400, same as {@link #getRate}
   * @throws ResponseStatusException 404 if neither a direct pair (either way round) nor a complete
   *     chain through {@value #INTERMEDIATE_CURRENCY} exists (PR-011: refuse rather than silently
   *     default to 1.0, in any chain) - a caller that would rather treat "no rate exists" as one
   *     possible outcome among several (e.g. an unresolvable line in a larger aggregation, not a
   *     failed request) should call {@link #tryGetConversionRate} instead of catching this:
   *     catching a {@code @Transactional} method's exception does not undo Spring's rollback-only
   *     marking of the enclosing transaction if the caller shares it (the two run in the same
   *     physical transaction by default, {@code REQUIRED} propagation) - the caller's own commit
   *     then fails with {@code UnexpectedRollbackException} regardless of the catch (confirmed
   *     against InstitutionService.getSummary, which hit exactly this - see #78's follow-up fix).
   */
  @Transactional(readOnly = true)
  public CurrencyConversionResult getConversionRate(
      String baseCurrency, String quoteCurrency, LocalDate date, String source) {
    return tryGetConversionRate(baseCurrency, quoteCurrency, date, source)
        .orElseThrow(() -> noConversionRateAvailable(baseCurrency, quoteCurrency, source, date));
  }

  /**
   * Non-throwing counterpart of {@link #getConversionRate} - {@link Optional#empty()} instead of a
   * 404 when nothing resolves, for a caller that needs to treat "no rate exists" as a value to
   * branch on rather than an exception to propagate (see {@link #getConversionRate}'s own Javadoc
   * for why catching the throwing form doesn't safely substitute for this within a shared
   * transaction). Still throws for a genuinely malformed request (400) - that is a defect in the
   * caller, not an ordinary "not found yet" outcome, and must not be swallowed the same way.
   */
  @Transactional(readOnly = true)
  public Optional<CurrencyConversionResult> tryGetConversionRate(
      String baseCurrency, String quoteCurrency, LocalDate date, String source) {
    requireValidCurrencyCode(baseCurrency, "baseCurrency");
    requireValidCurrencyCode(quoteCurrency, "quoteCurrency");
    requireNonNull(date, "date");
    requireNonBlank(source, "source");

    // Converting a currency to itself is always rate 1.0, by definition - checked before any rate
    // lookup at all, since neither a stored pair nor a chain is a correct way to answer this: a
    // chain through the intermediate would return whatever rate(base,EUR) * rate(EUR,base)
    // happens to multiply to - correct only if those two independently-sourced rates are exact
    // reciprocals, which real FX data has no reason to be.
    if (baseCurrency.equals(quoteCurrency)) {
      return Optional.of(
          new CurrencyConversionResult(
              BigDecimal.ONE, baseCurrency, quoteCurrency, date, true, null, false, false));
    }

    Optional<CurrencyConversionResult> resolved =
        resolve(baseCurrency, quoteCurrency, date, source);
    if (resolved.isEmpty() && fetchedMissingHistory(source, date)) {
      resolved = resolve(baseCurrency, quoteCurrency, date, source);
    }
    return resolved;
  }

  /**
   * {@code amount} in {@code baseCurrency} converted to {@code quoteCurrency}, using {@link
   * #getConversionRate}'s direct-pair-first resolution, rounded per this class's documented
   * NFR-CALC-007 money policy - applied once, here, never accumulated through the rate resolution
   * itself. A caller that also needs the resolution's own metadata (whether it was carried-forward
   * or chained, PR-011) alongside the converted amount - as {@code InstitutionService.getSummary}
   * does - should call {@link #getConversionRate} once and pass its result to {@link #applyRate}
   * instead, rather than calling this method a second time and re-resolving the same rate.
   */
  @Transactional(readOnly = true)
  public BigDecimal convert(
      BigDecimal amount, String baseCurrency, String quoteCurrency, LocalDate date, String source) {
    return applyRate(amount, getConversionRate(baseCurrency, quoteCurrency, date, source));
  }

  /**
   * Applies an already-resolved {@link CurrencyConversionResult} to {@code amount}, rounded per
   * this class's documented NFR-CALC-007 money policy - the other half of {@link #convert}, split
   * out so a caller that already called {@link #getConversionRate} for its metadata (carried-
   * forward/chained marking, PR-011) doesn't pay for a second, identical rate resolution just to
   * also get the converted amount.
   */
  public BigDecimal applyRate(BigDecimal amount, CurrencyConversionResult conversion) {
    requireNonNull(amount, "amount");
    requireNonNull(conversion, "conversion");
    return amount.multiply(conversion.rate()).setScale(MONEY_SCALE, MONEY_ROUNDING);
  }

  private Optional<CurrencyConversionResult> resolve(
      String baseCurrency, String quoteCurrency, LocalDate date, String source) {
    Optional<ResolvedFxRate> direct = leg(baseCurrency, quoteCurrency, date, source);
    if (direct.isPresent()) {
      return Optional.of(toResult(direct.get(), baseCurrency, quoteCurrency, date, null));
    }

    // One side is already the intermediate - there is no third currency left to chain through.
    if (INTERMEDIATE_CURRENCY.equals(baseCurrency) || INTERMEDIATE_CURRENCY.equals(quoteCurrency)) {
      return Optional.empty();
    }

    // The second leg's lookup must not run at all when the first is already missing - this is
    // exactly the path a currency nobody publishes takes.
    Optional<ResolvedFxRate> firstLeg = leg(baseCurrency, INTERMEDIATE_CURRENCY, date, source);
    if (firstLeg.isEmpty()) {
      return Optional.empty();
    }
    Optional<ResolvedFxRate> secondLeg = leg(INTERMEDIATE_CURRENCY, quoteCurrency, date, source);
    if (secondLeg.isEmpty()) {
      return Optional.empty();
    }
    ResolvedFxRate chain =
        new ResolvedFxRate(
            firstLeg.get().rate().multiply(secondLeg.get().rate(), MathContext.DECIMAL128),
            earlier(firstLeg.get().rateDate(), secondLeg.get().rateDate()));
    return Optional.of(toResult(chain, baseCurrency, quoteCurrency, date, INTERMEDIATE_CURRENCY));
  }

  // The stored from/to pair, or else the stored to/from pair inverted.
  private Optional<ResolvedFxRate> leg(String from, String to, LocalDate date, String source) {
    Optional<FxRate> stored = findOnOrBefore(from, to, source, date);
    if (stored.isPresent()) {
      return Optional.of(new ResolvedFxRate(stored.get().getRate(), stored.get().getRateDate()));
    }
    return findOnOrBefore(to, from, source, date)
        .map(
            reverse ->
                new ResolvedFxRate(
                    BigDecimal.ONE.divide(reverse.getRate(), MathContext.DECIMAL128),
                    reverse.getRateDate()));
  }

  private CurrencyConversionResult toResult(
      ResolvedFxRate leg,
      String baseCurrency,
      String quoteCurrency,
      LocalDate date,
      String intermediateCurrency) {
    return new CurrencyConversionResult(
        roundRate(leg.rate()),
        baseCurrency,
        quoteCurrency,
        date,
        intermediateCurrency == null,
        intermediateCurrency,
        !leg.rateDate().equals(date),
        isStale(leg.rateDate(), date));
  }

  // A stored rate keeps its own scale; only a derived one carries more digits than fx_rate holds.
  private static BigDecimal roundRate(BigDecimal rate) {
    return rate.scale() > RATE_SCALE ? rate.setScale(RATE_SCALE, RoundingMode.HALF_UP) : rate;
  }

  private boolean isStale(LocalDate rateDate, LocalDate requestedDate) {
    return rateDate.isBefore(requestedDate.minus(staleAfter));
  }

  private boolean fetchedMissingHistory(String source, LocalDate date) {
    return fxRateImportService.fetchesOnDemandFor(source)
        && fxRateImportService.fetchMissingHistory(date);
  }

  private static LocalDate earlier(LocalDate a, LocalDate b) {
    return a.isBefore(b) ? a : b;
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
