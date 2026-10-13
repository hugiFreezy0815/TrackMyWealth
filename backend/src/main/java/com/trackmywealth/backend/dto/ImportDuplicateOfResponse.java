package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The ledger row an import row duplicates (US-07-17), as the preview shows it next to the row: the
 * duplicate rule compares booking date, amount and currency only, so the member reads both
 * descriptions to decide whether to force the row in. Its id is the row's {@code
 * duplicateOfTransactionId}.
 */
public record ImportDuplicateOfResponse(
    LocalDate bookingDate, BigDecimal amount, String currency, String merchantDescription) {}
