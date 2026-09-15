package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.FxRateLookupResult;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import java.time.LocalDate;
import java.util.Currency;
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
 * <p>Deliberately does not resolve the inverse pair (e.g. answer a CHF-to-USD query from a stored
 * USD/CHF row) or chain through an intermediate currency - that is US-06-02's direct-pair
 * conversion contract, not this story's.
 */
@Service
public class FxRateService {

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
        fxRateRepository
            .findFirstByBaseCurrencyAndQuoteCurrencyAndSourceAndRateDateLessThanEqualOrderByRateDateDesc(
                baseCurrency, quoteCurrency, source, date)
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
