package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The parts of an {@code account_snapshot} that valuation reads (#380): which snapshot, its date
 * and its balance (the positive amount owed for a liability). Kept apart from the entity so a batch
 * of them can travel in {@link AccountValueSources}.
 */
public record SnapshotBalance(UUID snapshotId, LocalDate snapshotDate, BigDecimal balance) {}
