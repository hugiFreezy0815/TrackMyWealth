package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** The snapshot preview is live: another committed batch can make a previously new date duplicate. */
public record ImportStatementBalanceResponse(
    LocalDate date,
    BigDecimal amount,
    String currency,
    String status,
    UUID existingSnapshotId) {}
