package com.trackmywealth.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
 * <p>{@code PERSONAL_ASSETS} cannot be set: it belongs to the workspace's default portfolio, which
 * always keeps it, so that portfolio accepts only {@code null} here.
 *
 * <p>{@code name} and {@code logoUrl} are bounded as on {@link CreatePortfolioRequest}.
 *
 * <p>The currency, the catalogue link and the provider's kind are not changed here: they are chosen
 * when the portfolio is created (FR-INS-004).
 */
public record UpdatePortfolioRequest(
    @NotBlank @Size(max = CreatePortfolioRequest.MAX_NAME_LENGTH) String name,
    // ISO 3166-1 alpha-2, as CreatePortfolioRequest.country.
    @Pattern(regexp = "[A-Z]{2}") String country,
    @Size(max = CreatePortfolioRequest.MAX_LOGO_URL_LENGTH)
        @Pattern(regexp = CreatePortfolioRequest.LOGO_URL_PATTERN)
        String logoUrl,
    @JsonProperty(required = true) @Pattern(regexp = PortfolioType.SELECTABLE_PATTERN)
        String portfolioType) {

  public UpdatePortfolioRequest {
    name = RequestStrings.blankToNull(name);
    country = RequestStrings.blankToNull(country);
    logoUrl = RequestStrings.blankToNull(logoUrl);
    portfolioType = RequestStrings.blankToNull(portfolioType);
  }
}
