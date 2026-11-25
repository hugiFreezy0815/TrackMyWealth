package com.trackmywealth.backend.dto;

/**
 * US-15-15 (#714): where a position's cost basis comes from. {@code STATEMENT}: printed by an
 * imported statement (or reported by a provider); {@code ENTERED}: entered by the member with a
 * snapshot; {@code ORDERS}: calculated from the account's buy and sell orders, because the snapshot
 * has none.
 */
public enum CostBasisSource {
  STATEMENT,
  ENTERED,
  ORDERS
}
