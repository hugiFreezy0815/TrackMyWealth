package com.trackmywealth.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code PUT /api/v1/portfolios/{portfolioId}} (US-04-06): a full replacement of
 * what the member may change about a portfolio, sent with {@code If-Match} (ADR 0004). A {@code
 * null} {@code country} or {@code logoUrl} clears it.
 *
 * <p>{@code portfolioType} must be present: a type sets the portfolio's type and keeps it as set,
 * whatever accounts are added later; {@code null} goes back to the type suggested from the
 * portfolio's accounts. Required, so that a client which leaves the field out does not silently
 * drop the member's choice.
 *
 * <p>The currency, the catalogue link and the provider's kind are not changed here: they are chosen
 * when the portfolio is created (FR-INS-004).
 */
public record UpdatePortfolioRequest(
    @NotBlank String name,
    // ISO 3166-1 alpha-2, as CreatePortfolioRequest.country.
    @Pattern(regexp = "[A-Z]{2}") String country,
    String logoUrl,
    @JsonProperty(required = true) @Pattern(regexp = PortfolioType.PATTERN) String portfolioType) {

  public UpdatePortfolioRequest {
    name = RequestStrings.blankToNull(name);
    country = RequestStrings.blankToNull(country);
    logoUrl = RequestStrings.blankToNull(logoUrl);
    portfolioType = RequestStrings.blankToNull(portfolioType);
  }
}
