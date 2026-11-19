package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One cash or holdings component of a statement, read together for valuation. */
public interface ReportedHoldingValue {
  UUID getAccountId();

  LocalDate getSnapshotDate();

  /** Whether the statement listed cash in another currency that the snapshot did not record. */
  boolean isUnrecordedCash();

  @Nullable BigDecimal getReportedValue();

  @Nullable String getReportedCurrency();
}
