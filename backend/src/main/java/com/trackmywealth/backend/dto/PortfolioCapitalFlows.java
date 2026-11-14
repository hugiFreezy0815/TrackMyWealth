package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Dated external contributions and internal capital movement into a portfolio. */
public record PortfolioCapitalFlows(
    @Nullable BigDecimal contributions,
    @Nullable BigDecimal capitalMovements,
    boolean complete,
    List<HistoryWarning> warnings) {
  public PortfolioCapitalFlows {
    warnings = List.copyOf(warnings);
  }
}
