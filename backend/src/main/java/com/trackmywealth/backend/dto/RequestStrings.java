package com.trackmywealth.backend.dto;

import org.springframework.util.StringUtils;

/**
 * Shared normalization for optional {@code String} fields on request DTOs' compact constructors
 * (first extracted from {@code CreateFinancialInstitutionRequest} and {@code CreateAccountRequest},
 * which had each reimplemented the same method). Jakarta Validation's built-in constraints (e.g.
 * {@code @Pattern}, {@code ValidCurrencyCode}) already treat {@code null} as valid by convention,
 * deferring "required" to a separate {@code @NotNull}/{@code @NotBlank}, but a client sending
 * {@code ""} instead of omitting an optional/ignored field (a common default for an unset form
 * field) would otherwise fail validation for a value the request contract says doesn't matter.
 */
final class RequestStrings {

  private RequestStrings() {}

  static String blankToNull(String value) {
    return StringUtils.hasText(value) ? value : null;
  }
}
