package com.trackmywealth.backend.dto;

import java.math.BigDecimal;

/**
 * US-11-05: one Portfolio View section's signed subtotal across the entire workspace.
 *
 * @param section the shared Portfolio View placement, ordered as {@link PortfolioSection}
 * @param subtotal the sum of known values in the effective net-worth currency; liabilities are
 *     negative and unknown values are excluded, just as in {@code netWorth}
 * @param complete whether every active account in this section has a known value
 * @param valueStatus the worst {@link ValueStatus} of this section's accounts
 * @param accountCount the number of active accounts in this section, including unknown ones
 */
public record NetWorthSectionResponse(
    PortfolioSection section,
    BigDecimal subtotal,
    boolean complete,
    ValueStatus valueStatus,
    int accountCount) {}
