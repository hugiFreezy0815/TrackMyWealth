package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * US-07-12: the balances an import file states, per its template's {@link
 * ImportStatementBalanceRule}, in file order.
 *
 * @param balances each line the rule's pattern matched and whose date and amount could be read
 * @param unreadable how many lines the pattern matched whose date, amount or currency could not be
 *     read
 */
public record ImportStatementBalances(List<Stated> balances, int unreadable) {

  public ImportStatementBalances {
    balances = List.copyOf(balances);
  }

  /**
   * One balance as the file states it.
   *
   * @param amount signed as the account holder sees it: negative is overdrawn, or owed
   * @param currency upper case, or {@code null} when the rule's pattern has no currency group
   */
  public record Stated(LocalDate date, BigDecimal amount, String currency) {}
}
