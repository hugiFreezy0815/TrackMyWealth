package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * US-04-06: the per-kind details of a portfolio's accounts for the Portfolio View, each looked up
 * once for the whole portfolio, keyed by account id. An account without such a detail has no entry.
 *
 * @param positionsCount the positions in the newest holdings snapshot of an account holding them
 * @param positionsAsOf the date of that snapshot
 * @param dueDates the current statement's due date of a credit card with a configured cycle
 * @param linkedProperties the property account a mortgage finances
 */
public record PortfolioAccountDetails(
    Map<UUID, Integer> positionsCount,
    Map<UUID, LocalDate> positionsAsOf,
    Map<UUID, LocalDate> dueDates,
    Map<UUID, UUID> linkedProperties) {

  public PortfolioAccountDetails {
    // Defensive/immutable copies (SpotBugs EI_EXPOSE_REP).
    positionsCount = Map.copyOf(positionsCount);
    positionsAsOf = Map.copyOf(positionsAsOf);
    dueDates = Map.copyOf(dueDates);
    linkedProperties = Map.copyOf(linkedProperties);
  }
}
