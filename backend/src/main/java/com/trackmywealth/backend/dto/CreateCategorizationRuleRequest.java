package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/categorization-rules} (US-08-01, FR-CAT-007).
 *
 * <ul>
 *   <li>{@code matchType} {@code MERCHANT}: {@code matchValue} is found anywhere in a transaction's
 *       merchant description, ignoring case and repeated whitespace ({@code "migros"} matches
 *       {@code "MIGROS ZUERICH 123"}).
 *   <li>{@code matchType} {@code SOURCE_CODE}: {@code matchValue} is one source code, prefixed by
 *       its standard - {@code MCC:5812}, {@code ISO20022_PURPOSE:SALA} or {@code
 *       ISO20022_BTC:PMNT-RCDT-ESCT}.
 * </ul>
 *
 * {@code COUNTERPARTY_IBAN} and {@code AMOUNT_PATTERN} (V13) are rejected until imports supply a
 * counterparty and an agreed pattern format. {@code priority} orders rules, lowest first (default
 * 100); a rule is never edited, only deactivated and replaced.
 */
public record CreateCategorizationRuleRequest(
    @NotBlank String matchType,
    @NotBlank @Size(max = 255) String matchValue,
    @NotNull UUID categoryId,
    @Min(0) @Max(10_000) Integer priority) {

  public CreateCategorizationRuleRequest {
    matchType = RequestStrings.blankToNull(matchType);
    matchValue = RequestStrings.blankToNull(matchValue);
  }
}
