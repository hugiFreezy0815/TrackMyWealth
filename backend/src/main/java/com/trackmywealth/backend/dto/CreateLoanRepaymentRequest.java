package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/accounts/{loanAccountId}/repayments} (US-10-02): one payment
 * from {@code payingAccountId}, an ordinary account of the same workspace, to the loan or mortgage
 * in the path.
 *
 * <p>{@code amount} is what was paid, as a positive magnitude - the service writes the
 * cash-direction signed rows. It must be in the currency both accounts share. {@code
 * interestAmount} is optional: the interest part when the source states it (e.g. a statement that
 * books interest and instalment separately), from zero up to {@code amount}; without it, the split
 * comes from the loan's amortisation schedule or an estimate ({@code LoanRepaymentService}).
 *
 * <p>{@code externalId} is an optional idempotency key, as on {@link CreateTransactionRequest}: a
 * retry with the same key and the same payment returns the payment first recorded; the same key
 * with another payment is a 409. Optional strings are normalized blank-to-{@code null}.
 */
public record CreateLoanRepaymentRequest(
    @NotNull UUID payingAccountId,
    @NotNull LocalDate bookingDate,
    @NotNull @Digits(integer = 16, fraction = 4) BigDecimal amount,
    @NotBlank @ValidCurrencyCode String currency,
    @Digits(integer = 16, fraction = 4) BigDecimal interestAmount,
    @Size(max = 255) String merchantDescription,
    @Size(max = 2000) String notes,
    @Size(max = 255) String externalId) {

  public CreateLoanRepaymentRequest {
    currency = RequestStrings.blankToNull(currency);
    merchantDescription = RequestStrings.blankToNull(merchantDescription);
    notes = RequestStrings.blankToNull(notes);
    externalId = RequestStrings.blankToNull(externalId);
  }
}
