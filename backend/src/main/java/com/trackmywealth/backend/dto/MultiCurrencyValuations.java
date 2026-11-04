package com.trackmywealth.backend.dto;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * US-04-06: the same accounts valued in several currencies at once ({@code
 * AccountValuationService#valueAllIn}): each account's value is resolved once and only converted
 * per currency. Kept in {@code dto}, not nested in the service, which ArchUnit's service naming
 * rule forbids.
 *
 * @param byCurrency per requested currency, every account's valuation in it, in the order the
 *     accounts were given
 * @param inOwnCurrency every account's valuation in its own currency (a card's billing currency),
 *     in the same order
 */
public record MultiCurrencyValuations(
    Map<String, List<AccountValuation>> byCurrency, List<AccountValuation> inOwnCurrency) {

  public MultiCurrencyValuations {
    // Defensive/immutable copies (SpotBugs EI_EXPOSE_REP).
    Map<String, List<AccountValuation>> copy = new LinkedHashMap<>();
    byCurrency.forEach((currency, valuations) -> copy.put(currency, List.copyOf(valuations)));
    byCurrency = Map.copyOf(copy);
    inOwnCurrency = List.copyOf(inOwnCurrency);
  }

  /** The valuations in {@code currency}, which must have been requested. */
  public List<AccountValuation> in(String currency) {
    List<AccountValuation> valuations = byCurrency.get(currency);
    if (valuations == null) {
      throw new IllegalArgumentException("Not valued in " + currency);
    }
    return valuations;
  }

  /** The valuations in {@code currency}, which must have been requested, keyed by account id. */
  public Map<UUID, AccountValuation> byAccountIn(String currency) {
    return byAccount(in(currency));
  }

  /** The valuations in each account's own currency, keyed by account id. */
  public Map<UUID, AccountValuation> byAccountInOwnCurrency() {
    return byAccount(inOwnCurrency);
  }

  /**
   * The valuations of {@code accountIds}, in that order, from a {@link #byAccountIn} map: how a
   * group of accounts (a portfolio, a section) picks its own out of a batch valuation, by id rather
   * than by position.
   */
  public static List<AccountValuation> select(
      Collection<UUID> accountIds, Map<UUID, AccountValuation> byAccount) {
    return accountIds.stream().map(byAccount::get).toList();
  }

  private static Map<UUID, AccountValuation> byAccount(List<AccountValuation> valuations) {
    Map<UUID, AccountValuation> byAccount = new HashMap<>();
    for (AccountValuation valuation : valuations) {
      byAccount.put(valuation.accountId(), valuation);
    }
    return byAccount;
  }
}
