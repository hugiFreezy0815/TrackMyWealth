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

  @Nullable BigDecimal getReportedValue();

  @Nullable String getReportedCurrency();
}
