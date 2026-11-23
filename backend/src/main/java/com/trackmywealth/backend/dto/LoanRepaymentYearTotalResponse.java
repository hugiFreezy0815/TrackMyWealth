package com.trackmywealth.backend.dto;

import java.math.BigDecimal;

/**
 * #693: what was paid to a loan or mortgage in one calendar year of booking dates, from its
 * repayments still in effect (US-10-02).
 *
 * @param year the calendar year of the payments' booking dates
 * @param amountPaid every payment of the year, a positive magnitude
 * @param principalPaid the principal parts the split recorded; a payment whose split is unknown
 *     adds nothing here or to {@code interestPaid}, only to {@code amountPaid}
 * @param interestPaid the interest parts the split recorded
 * @param paymentCount how many payments the year has
 * @param splitEstimated {@code true} when at least one of the year's payments has {@code
 *     splitEstimated} - an estimated, schedule-differing or unknown split; a client must then show
 *     the principal and interest as estimates
 */
public record LoanRepaymentYearTotalResponse(
    int year,
    BigDecimal amountPaid,
    BigDecimal principalPaid,
    BigDecimal interestPaid,
    long paymentCount,
    boolean splitEstimated) {}
