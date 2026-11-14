package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Historical net contributions, completeness and data-quality warnings. */
public record CashFlowContribution(
    @Nullable BigDecimal amount, boolean complete, List<HistoryWarning> warnings) {
  public CashFlowContribution {
    warnings = List.copyOf(warnings);
  }
}
