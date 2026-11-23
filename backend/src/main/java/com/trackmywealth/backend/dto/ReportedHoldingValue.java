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

  /** Whether this component is a position; otherwise it is statement cash (#440). */
  boolean isHolding();

  /** A position's reported cost basis; always null for cash. */
  @Nullable BigDecimal getReportedCostBasis();

  /** #527: the currency of the cost basis; null when unknown, and always for cash. */
  @Nullable String getReportedCostBasisCurrency();

  /** #527: a foreign cost basis in the account currency at the purchase-date rate(s), if known. */
  @Nullable BigDecimal getCostBasisInAccountCurrency();
}
