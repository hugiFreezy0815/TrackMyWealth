package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The reconciliation signal shown with an account (FR-REC-005).
 *
 * @param status one of {@link ReconciliationStatusValues}
 * @param reason why a {@code NOT_RECONCILABLE} account cannot currently be compared
 * @param asOf snapshot date; hidden for a {@code BALANCE_ONLY} grant
 * @param openDifference snapshot minus derived ledger balance when the status is {@code
 *     OPEN_DIFFERENCE}; hidden for a {@code BALANCE_ONLY} grant
 * @param currency ISO currency of {@code openDifference}; null when no amount is exposed
 */
public record ReconciliationStatusResponse(
    String status, String reason, LocalDate asOf, BigDecimal openDifference, String currency) {}
