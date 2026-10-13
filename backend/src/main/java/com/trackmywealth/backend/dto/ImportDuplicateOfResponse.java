package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The ledger row an import row duplicates (US-07-17), as the preview shows it next to the row: the
 * duplicate rule compares booking date, amount and currency only, so the member reads both
 * descriptions to decide whether to force the row in.
 */
public record ImportDuplicateOfResponse(
    UUID transactionId,
    LocalDate bookingDate,
    BigDecimal amount,
    String currency,
    String merchantDescription) {}
