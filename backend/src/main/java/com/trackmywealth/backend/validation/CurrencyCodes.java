package com.trackmywealth.backend.validation;

import java.util.Currency;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one ISO 4217 check (#224 review): {@link CurrencyCodeValidator} ({@code @ValidCurrencyCode}
 * on request bodies), {@code FxRateService} and every service taking a currency as a query
 * parameter use it, so they cannot disagree on what a currency code is.
 */
public final class CurrencyCodes {

  private CurrencyCodes() {}

  /** Whether {@code code} is an ISO 4217 code {@link Currency} knows. */
  public static boolean isValid(String code) {
    if (code == null) {
      return false;
    }
    try {
      Currency.getInstance(code);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** {@code code}, or 400 when it is missing or not an ISO 4217 code. */
  public static String requireValid(String code, String fieldName) {
    if (code == null || code.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required.");
    }
    if (!isValid(code)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          fieldName + quoted(code) + " is not a valid ISO 4217 currency code.");
    }
    return code;
  }

  /**
   * {@link #requireValid}, and 400 for an ISO 4217 code that is no money - a precious metal, a fund
   * or a test code ({@code XAU}, {@code XDR}, {@code XXX}; no minor unit). For a stored setting
   * every total is shown in, such as the workspace currency (#224): no rate source quotes these, so
   * every converted figure would be unknown for good. Retired codes ({@code DEM}) cannot be told
   * apart through {@link Currency} and still pass; their figures are unknown, never wrong.
   */
  public static String requireMonetary(String code, String fieldName) {
    requireValid(code, fieldName);
    if (Currency.getInstance(code).getDefaultFractionDigits() < 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          fieldName + quoted(code) + " is not a currency figures can be shown in.");
    }
    return code;
  }

  // The rejected value is echoed only while it is short enough to be a code: a problem detail must
  // not reflect arbitrary caller input of any length.
  private static String quoted(String code) {
    return code.length() <= 3 ? " '" + code + "'" : "";
  }

  /**
   * The requested {@code currency} query parameter (validated), or {@code defaultCurrency} when it
   * is absent. An empty parameter ({@code ?currency=}) counts as absent, not as an invalid code.
   */
  public static String requestedOrDefault(String requested, String defaultCurrency) {
    return requested == null || requested.isBlank()
        ? defaultCurrency
        : requireValid(requested, "currency");
  }
}
