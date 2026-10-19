package com.trackmywealth.backend.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One parsed data row in the ledger's terms (US-07-03): what US-07-04 turns into a transaction.
 * {@code amount} is signed (negative leaves the account) and keeps the scale the file wrote it
 * with. {@code currency} is {@code null} only for currency mode {@code FROM_ACCOUNT} when no
 * account currency was given (the template dry run). Text fields are trimmed; an empty cell is
 * {@code null}.
 *
 * <p>{@code valueDateAdjusted} (#334): the file wrote the value date as a day of February that does
 * not exist - the 29th in a common year or the 30th, the 30/360 interest convention, in which every
 * month has 30 days - and {@code valueDate} is the real last day of that February. The row warns
 * about it ({@code IMPORT_ROW_VALUE_DATE_ADJUSTED}). Rows stored before it existed have no such
 * property; they read as {@code false} rather than failing Jackson 3's {@code
 * FAIL_ON_NULL_FOR_PRIMITIVES}.
 *
 * <p>US-07-22 (#325): {@code groupReference} is the reference the bank gives the bookings that
 * belong together - both legs of a currency exchange - never this booking's own id. {@code
 * savingsPocket}: the row moves money between the account and its savings pocket (the template's
 * {@code savingsPocketPattern}); it is a {@code TRANSFER}, and the commit books the other leg on
 * the pocket. Rows stored before either existed read as {@code null} and {@code false}.
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
    String notes,
    BigDecimal originalAmount,
    String originalCurrency,
    BigDecimal bankExchangeRate,
    @JsonSetter(nulls = Nulls.AS_EMPTY) boolean valueDateAdjusted,
    String groupReference,
    @JsonSetter(nulls = Nulls.AS_EMPTY) boolean savingsPocket) {

  /** A row that belongs to no group of bookings and is no savings pocket move. */
  public CanonicalImportRow(
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
      String notes,
      BigDecimal originalAmount,
      String originalCurrency,
      BigDecimal bankExchangeRate,
      boolean valueDateAdjusted) {
    this(
        bookingDate,
        valueDate,
        amount,
        currency,
        transactionType,
        description,
        counterpartyName,
        externalId,
        mcc,
        iso20022BankTransactionCode,
        notes,
        originalAmount,
        originalCurrency,
        bankExchangeRate,
        valueDateAdjusted,
        null,
        false);
  }

  /**
   * A row whose value date is as the file wrote it.
   *
   * @see #valueDateAdjusted()
   */
  public CanonicalImportRow(
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
      String notes,
      BigDecimal originalAmount,
      String originalCurrency,
      BigDecimal bankExchangeRate) {
    this(
        bookingDate,
        valueDate,
        amount,
        currency,
        transactionType,
        description,
        counterpartyName,
        externalId,
        mcc,
        iso20022BankTransactionCode,
        notes,
        originalAmount,
        originalCurrency,
        bankExchangeRate,
        false);
  }

  /** Legacy cash templates have no separately disclosed original-currency amount. */
  public CanonicalImportRow(
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
      String notes) {
    this(
        bookingDate,
        valueDate,
        amount,
        currency,
        transactionType,
        description,
        counterpartyName,
        externalId,
        mcc,
        iso20022BankTransactionCode,
        notes,
        null,
        null,
        null);
  }
}
