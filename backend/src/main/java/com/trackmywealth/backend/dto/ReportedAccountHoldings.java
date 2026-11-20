package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Position count, valuation and unrealised gain from the same holdings statement (#440). */
public record ReportedAccountHoldings(
    LocalDate snapshotDate,
    int positionsCount,
    @Nullable NativeAccountValue value,
    @Nullable BigDecimal totalReturn,
    @Nullable BigDecimal totalReturnPercent,
    @Nullable Integer missingCostBasisCount,
    @Nullable Integer foreignCurrencyCostBasisCount) {

  public ReportedAccountHoldings(LocalDate snapshotDate, int positionsCount) {
    this(snapshotDate, positionsCount, null, null, null, null, null);
  }
}
