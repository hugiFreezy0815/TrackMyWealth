package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.Map;

/** Internal dated valuation result, kept out of the service-only package. */
public record ValueHistorySnapshot(
    BigDecimal value,
    boolean complete,
    boolean estimated,
    Map<PortfolioSection, BigDecimal> sections) {
  public ValueHistorySnapshot {
    sections = Map.copyOf(sections);
  }
}
