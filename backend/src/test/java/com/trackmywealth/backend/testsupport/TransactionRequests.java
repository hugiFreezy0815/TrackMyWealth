package com.trackmywealth.backend.testsupport;

import com.trackmywealth.backend.dto.CreateTransactionRequest;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Builds {@link CreateTransactionRequest}s for tests. A cash or card entry carries none of the
 * investment fields (US-07-01); keeping this shortcut here rather than as an overload on the
 * request record keeps the production contract free of test conveniences.
 */
public final class TransactionRequests {

  private TransactionRequests() {}

  /** A cash or card entry: every investment field is {@code null}. */
  public static CreateTransactionRequest cash(
      String transactionType,
      LocalDate bookingDate,
      BigDecimal amount,
      String currency,
      String merchantDescription,
      String mcc,
      String notes,
      String externalId,
      BigDecimal fxRateToAccountCurrency,
      BigDecimal billedAmount,
      BigDecimal feeAmount) {
    return new CreateTransactionRequest(
        transactionType,
        bookingDate,
        amount,
        currency,
        merchantDescription,
        mcc,
        notes,
        externalId,
        fxRateToAccountCurrency,
        billedAmount,
        feeAmount,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** {@code request} with its {@code counterpartyName} (US-07-35) set to {@code name}. */
  public static CreateTransactionRequest withCounterpartyName(
      CreateTransactionRequest request, String name) {
    return new CreateTransactionRequest(
        request.transactionType(),
        request.bookingDate(),
        request.amount(),
        request.currency(),
        request.merchantDescription(),
        request.mcc(),
        request.notes(),
        request.externalId(),
        request.fxRateToAccountCurrency(),
        request.billedAmount(),
        request.feeAmount(),
        request.securityId(),
        request.quantity(),
        request.unitPrice(),
        request.tradeDate(),
        request.settlementDate(),
        request.grossAmount(),
        request.taxWithheldAmount(),
        request.counterpartyAccountId(),
        request.counterpartyAmount(),
        name);
  }
}
