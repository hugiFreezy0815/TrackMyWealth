package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * US-04-06: how many positions an account's newest holdings snapshot lists - a native-query
 * projection of {@code SnapshotHoldingRepository#countPositionsInNewestHoldingsSnapshot}. Kept in
 * {@code dto}, not nested in the repository, which ArchUnit's repository naming rule forbids.
 */
public interface AccountPositionCount {

  UUID getAccountId();

  long getPositions();
}
