package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for {@code POST} (record) and {@code PUT} (replace) on {@code
 * /api/v1/accounts/{accountId}/opening-balance} (US-25-04): the account's balance at the end of
 * {@code date}. Rows booked on {@code date} itself are taken as already contained in it; only rows
 * booked later are added to it.
 *
 * <p>{@code balance} uses the same convention as {@code GET /accounts/{id}/balance}: a liability's
 * balance is the positive amount owed. {@code currency} is sent, not derived, so a client cannot
 * record a figure in a currency it did not mean: it must equal the account's own currency (a credit
 * card's billing currency), otherwise 422 - a conversion is not an opening balance.
 *
 * @param acknowledgeEarlierTransactions {@code true} to record the opening balance although live
 *     transactions are booked before {@code date}; those rows are then left out of the balance and
 *     the account carries {@link DataQualityWarningValues#TRANSACTIONS_BEFORE_OPENING_BALANCE}.
 *     Absent or {@code false}, such rows make the request a 409 that names them.
 */
public record OpeningBalanceRequest(
    @NotNull LocalDate date,
    @NotNull @Digits(integer = 16, fraction = 4) BigDecimal balance,
    @NotBlank @ValidCurrencyCode String currency,
    Boolean acknowledgeEarlierTransactions) {

  public boolean acknowledgesEarlierTransactions() {
    return Boolean.TRUE.equals(acknowledgeEarlierTransactions);
  }
}
