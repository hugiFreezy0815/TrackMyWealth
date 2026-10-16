package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * US-10-02: the ledger rows one loan payment writes, as {@code LoanRepaymentService} decided them,
 * for {@code TransactionService#recordLoanPayment}. Both amounts are positive magnitudes, each
 * {@code null} when that part has no row.
 *
 * @param principal the {@code DEBT_REPAYMENT} pair's amount: the principal, or the whole payment
 *     while its split is unknown
 * @param interest the interest {@code EXPENSE}
 * @param splitKnown whether the pair is a resolved internal transfer; an unknown split stays
 *     pending review
 */
public record LoanPaymentPlan(
    LocalDate bookingDate,
    String currency,
    BigDecimal principal,
    BigDecimal interest,
    boolean splitKnown,
    String merchantDescription,
    String notes) {}
