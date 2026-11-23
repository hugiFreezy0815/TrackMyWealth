package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * #693: one calendar year of a loan's payments, as {@code LoanPaymentRepository} sums them. {@code
 * principal} and {@code interest} are null when no payment of the year has a known split; {@code
 * unknownSplitAmount} is null when every payment has one; {@code estimatedPayments} counts the
 * payments flagged {@code splitEstimated}.
 */
public record LoanRepaymentYearSum(
    Integer year,
    BigDecimal amount,
    @Nullable BigDecimal principal,
    @Nullable BigDecimal interest,
    @Nullable BigDecimal unknownSplitAmount,
    Long payments,
    Long estimatedPayments) {}
