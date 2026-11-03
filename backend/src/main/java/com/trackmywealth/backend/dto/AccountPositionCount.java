package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * US-04-06: how many positions an account's newest holdings snapshot lists, and its date - a
 * native-query projection of {@code
 * SnapshotHoldingRepository#countPositionsInNewestHoldingsSnapshot}. Kept in {@code dto}, not
 * nested in the repository, which ArchUnit's repository naming rule forbids.
 */
public interface AccountPositionCount {

  UUID getAccountId();

  long getPositions();

  /** The date of the snapshot the positions are counted in. */
  LocalDate getPositionsAsOf();
}
