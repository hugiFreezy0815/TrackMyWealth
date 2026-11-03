package com.trackmywealth.backend.dto;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

  /** No accounts, valued in each of {@code currencies}: every list is empty. */
  public static MultiCurrencyValuations none(Collection<String> currencies) {
    Map<String, List<AccountValuation>> byCurrency = new LinkedHashMap<>();
    currencies.forEach(currency -> byCurrency.put(currency, List.of()));
    return new MultiCurrencyValuations(byCurrency, List.of());
  }

  /** The valuations in {@code currency}, which must have been requested. */
  public List<AccountValuation> in(String currency) {
    List<AccountValuation> valuations = byCurrency.get(currency);
    if (valuations == null) {
      throw new IllegalArgumentException("Not valued in " + currency);
    }
    return valuations;
  }
}
