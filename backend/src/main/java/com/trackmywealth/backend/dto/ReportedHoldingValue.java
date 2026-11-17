package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One holding and its parent statement's cash balance, read together for valuation. */
public interface ReportedHoldingValue {
  UUID getAccountId();

  LocalDate getSnapshotDate();

  @Nullable BigDecimal getCashBalance();

  /** Whether the statement listed cash in another currency that the snapshot did not record. */
  boolean isUnrecordedCash();

  @Nullable BigDecimal getReportedValue();

  @Nullable String getReportedCurrency();
}
