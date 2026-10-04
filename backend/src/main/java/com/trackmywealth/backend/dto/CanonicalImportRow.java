package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One parsed data row in the ledger's terms (US-07-03): what US-07-04 turns into a transaction.
 * {@code amount} is signed (negative leaves the account) and keeps the scale the file wrote it
 * with. {@code currency} is {@code null} only for currency mode {@code FROM_ACCOUNT} when no
 * account currency was given (the template dry run). Text fields are trimmed; an empty cell is
 * {@code null}.
 */
public record CanonicalImportRow(
    LocalDate bookingDate,
    LocalDate valueDate,
    BigDecimal amount,
    String currency,
    String transactionType,
    String description,
    String counterpartyName,
    String externalId,
    String mcc,
    String iso20022BankTransactionCode,
    String notes) {}
