package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * A portfolio as created, read or updated through {@code /api/v1/portfolios} (US-04-01, US-04-06).
 * Single-resource responses also send {@code ETag: "<version>"} (ADR 0004).
 *
 * @param catalogueInstitutionId the institution catalogue entry behind the portfolio; {@code null}
 *     for a portfolio without one (FR-INS-003)
 * @param institutionType the kind of provider behind the portfolio, e.g. {@code BANK} or {@code
 *     BROKER} - {@code PERSONAL_ASSETS} only for the workspace's default portfolio
 * @param portfolioType what the portfolio holds, for display only (FR-INS-006/008)
 * @param portfolioTypeOverridden {@code true} when the member set {@code portfolioType}; otherwise
 *     it is the type suggested from the portfolio's active accounts and follows them
 */
public record PortfolioResponse(
    UUID id,
    UUID catalogueInstitutionId,
    String name,
    String country,
    String institutionType,
    String identifier,
    String logoUrl,
    String portfolioCurrency,
    PortfolioType portfolioType,
    boolean portfolioTypeOverridden,
    boolean personalAssetsDefault,
    String status,
    int version) {}
