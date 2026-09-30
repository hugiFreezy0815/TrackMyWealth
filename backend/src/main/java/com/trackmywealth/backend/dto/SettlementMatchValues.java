package com.trackmywealth.backend.dto;

import java.util.Set;

/**
 * The closed value sets of {@code settlement_match} (V30): its lifecycle {@code status} and the
 * {@code match_basis} that produced it. Kept as constants alongside the DB {@code CHECK}s they
 * mirror, same as {@link AccessLevelValues}.
 */
public final class SettlementMatchValues {

  /** Awaiting a member's decision - ambiguous, or only one leg exists (FR-CF-005). */
  public static final String PROPOSED = "PROPOSED";

  /** Applied: both legs (or the one known leg) are flagged as an internal transfer. */
  public static final String CONFIRMED = "CONFIRMED";

  /** Declined or undone (FR-CF-004); never proposed again. */
  public static final String REJECTED = "REJECTED";

  /** The payment and a card-side credit of the same amount, both recorded. */
  public static final String LEG_PAIR = "LEG_PAIR";

  /** Only the payment is recorded, and it equals the card's balance on that date. */
  public static final String BALANCE_EQUALS_PAYMENT = "BALANCE_EQUALS_PAYMENT";

  /** A card payment and its settlement credit (US-09-02). */
  public static final String CARD_SETTLEMENT = "CARD_SETTLEMENT";

  /** US-10-01: the two legs of a transfer between two of the workspace's own accounts. */
  public static final String TRANSFER = "TRANSFER";

  /** Every kind of match (V41) - the one place the closed set is spelled out. */
  public static final Set<String> MATCH_KINDS = Set.of(CARD_SETTLEMENT, TRANSFER);

  /** Every status a match can have - the one place the closed set is spelled out. */
  public static final Set<String> STATUSES = Set.of(PROPOSED, CONFIRMED, REJECTED);

  private SettlementMatchValues() {}
}
