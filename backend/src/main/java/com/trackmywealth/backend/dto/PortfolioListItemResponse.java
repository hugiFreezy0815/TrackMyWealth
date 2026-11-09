package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code GET /api/v1/portfolios} (US-04-05, #381): a portfolio of the workspace with its
 * totals, for the portfolio tabs (FR-INS-013) and the first level of the navigation tree
 * (FR-NAV-01).
 *
 * <p>The portfolio's own fields are named as in {@link PortfolioResponse}. <b>Every amount here is
 * signed</b>, as in {@link PortfolioViewResponse}: a portfolio holding a property with a larger
 * mortgage has a negative total (FR-NAV-15). The totals are the same {@link PortfolioTotals} as
 * that portfolio's View, so the list and the tab never disagree, and the {@code
 * totalInWorkspaceCurrency} of all rows add up to net worth (US-19-02).
 *
 * @param version the portfolio's version, for an {@code If-Match} on a later change
 * @param catalogueInstitutionId the institution catalogue entry behind the portfolio; {@code null}
 *     for a portfolio without one (FR-INS-003)
 * @param institutionType the kind of provider behind the portfolio, e.g. {@code BANK} or {@code
 *     BROKER} - {@code PERSONAL_ASSETS} only for the workspace's default portfolio
 * @param portfolioType what the portfolio holds, for display only (FR-INS-006/014)
 * @param portfolioTypeOverridden {@code true} when the member set {@code portfolioType}; otherwise
 *     it is the type suggested from the portfolio's active accounts
 * @param personalAssetsDefault {@code true} for the workspace's Personal Assets portfolio
 *     (FR-NAV-16), which every workspace has
 * @param activeAccountCount the number of active accounts in the portfolio; {@code total} sums
 *     those of them whose value is known
 * @param portfolioCurrency the currency of {@code total}
 * @param total the signed sum of the known values of the active accounts, in {@code
 *     portfolioCurrency}; {@code 0} for a portfolio without accounts
 * @param workspaceCurrency the workspace's currency (US-06-05), the one net worth is shown in
 * @param totalInWorkspaceCurrency the same in {@code workspaceCurrency}
 * @param complete {@code false} when an account is left out of either total for want of a value
 * @param valueStatus the worst {@link ValueStatus} of the portfolio's accounts in either total's
 *     currency; {@code CURRENT} for a portfolio without accounts
 * @param asOf the business date every value is taken on
 * @param institutionName the catalogue institution's display name; null without a catalogue link
 */
public record PortfolioListItemResponse(
    UUID id,
    int version,
    String name,
    @Nullable UUID catalogueInstitutionId,
    String institutionType,
    @Nullable String country,
    @Nullable String logoUrl,
    PortfolioType portfolioType,
    boolean portfolioTypeOverridden,
    boolean personalAssetsDefault,
    int activeAccountCount,
    String portfolioCurrency,
    BigDecimal total,
    String workspaceCurrency,
    BigDecimal totalInWorkspaceCurrency,
    boolean complete,
    ValueStatus valueStatus,
    LocalDate asOf,
    @Nullable String institutionName) {}
