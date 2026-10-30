package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The reconciliation signal shown with an account (FR-REC-005).
 *
 * @param status one of {@link ReconciliationStatusValues}
 * @param reason why a {@code NOT_RECONCILABLE} account cannot currently be compared
 * @param asOf date of the snapshot the status compares against
 * @param openDifference snapshot minus derived ledger balance when the status is {@code
 *     OPEN_DIFFERENCE}, or the dismissed difference when it is {@code DISMISSED_DIFFERENCE}
 * @param currency ISO currency of {@code openDifference}; null when there is no amount
 */
public record ReconciliationStatusResponse(
    String status, String reason, LocalDate asOf, BigDecimal openDifference, String currency) {}
