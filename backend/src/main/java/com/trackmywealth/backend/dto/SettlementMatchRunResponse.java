package com.trackmywealth.backend.dto;

/**
 * What one run of a card's settlement matching changed (#473): the proposals it added and the
 * matches it confirmed itself (an exact, unambiguous pair, which the system applies without
 * asking). Matches that already existed are not counted, so repeating an idempotent run answers
 * zero twice. The matches themselves are read from the paged {@code GET
 * /api/v1/settlement-matches}.
 */
public record SettlementMatchRunResponse(int newlyProposed, int newlyConfirmed) {

  /** A run that changed nothing, such as one for a card without a usable settlement source. */
  public static final SettlementMatchRunResponse NOTHING = new SettlementMatchRunResponse(0, 0);
}
