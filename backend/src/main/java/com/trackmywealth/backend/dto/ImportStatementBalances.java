package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * US-07-12: the balances an import file states, per its template's {@link
 * ImportStatementBalanceRule}, in file order.
 *
 * @param balances each line the rule's pattern matched and whose date and amount could be read
 * @param unreadableCurrencies for each line the pattern matched whose date, amount or currency
 *     could not be read, the currency it names, or {@code null} when that is unknown (no currency
 *     group, or the currency itself unreadable): only an unreadable line that may be in the
 *     account's currency hides its balance
 * @param fileOrdered whether the closing balance is the last one in file order (a PDF's balance
 *     lines, printed in statement order) rather than the latest dated one (a CSV file's)
 */
public record ImportStatementBalances(
    List<Stated> balances, List<String> unreadableCurrencies, boolean fileOrdered) {

  /** Dated selection, with {@code unreadable} lines of unknown currency. */
  public ImportStatementBalances(List<Stated> balances, int unreadable) {
    this(balances, Collections.nCopies(unreadable, null), false);
  }

  public ImportStatementBalances {
    balances = List.copyOf(balances);
    // Not List.copyOf: an unknown currency is a null entry.
    unreadableCurrencies = Collections.unmodifiableList(new ArrayList<>(unreadableCurrencies));
  }

  /** How many matched lines could not be read, in any currency. */
  public int unreadable() {
    return unreadableCurrencies.size();
  }

  /** How many matched lines could not be read that may state a balance in {@code currency}. */
  public long unreadableIn(String currency) {
    return unreadableCurrencies.stream()
        .filter(named -> named == null || Objects.equals(named, currency))
        .count();
  }

  /**
   * One balance as the file states it.
   *
   * @param amount signed as the account holder sees it: negative is overdrawn, or owed
   * @param currency upper case, or {@code null} when the rule's pattern has no currency group
   */
  public record Stated(LocalDate date, BigDecimal amount, String currency) {}
}
