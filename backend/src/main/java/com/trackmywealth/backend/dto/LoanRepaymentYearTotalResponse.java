package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * #693: what was paid to a loan or mortgage in one calendar year of booking dates, from its
 * repayments still in effect (US-10-02). {@code amountPaid = principalPaid + interestPaid +
 * unknownSplitAmount}, a null part counting as zero.
 *
 * @param year the calendar year of the payments' booking dates
 * @param amountPaid every payment of the year, a positive magnitude
 * @param principalPaid the principal parts of the payments with a known split; {@code null} when no
 *     payment of the year has one, so a client shows "unknown", never 0
 * @param interestPaid the interest parts of the payments with a known split; {@code null} exactly
 *     when {@code principalPaid} is
 * @param unknownSplitAmount the payments whose split is unknown, in neither {@code principalPaid}
 *     nor {@code interestPaid}; zero when every payment of the year has a split
 * @param paymentCount how many payments the year has
 * @param splitEstimated {@code true} when at least one of the year's payments has {@code
 *     splitEstimated} - an estimated, schedule-differing or unknown split; a client must then show
 *     the principal and interest as estimates
 */
public record LoanRepaymentYearTotalResponse(
    int year,
    BigDecimal amountPaid,
    @Nullable BigDecimal principalPaid,
    @Nullable BigDecimal interestPaid,
    BigDecimal unknownSplitAmount,
    long paymentCount,
    boolean splitEstimated) {}
