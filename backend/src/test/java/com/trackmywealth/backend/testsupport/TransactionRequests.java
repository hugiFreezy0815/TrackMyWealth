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
        null);
  }
}
