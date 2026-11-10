package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** US-11-06: server-calculated history for net worth or one portfolio, never client-derived. */
public record ValueHistoryResponse(
    String currency,
    String period,
    LocalDate from,
    LocalDate to,
    List<Point> points,
    Change change,
    List<SectionChange> sections,
    boolean complete,
    List<String> warnings) {

  public ValueHistoryResponse {
    points = List.copyOf(points);
    sections = List.copyOf(sections);
    warnings = List.copyOf(warnings);
  }

  /** A bounded sampled value. Unknown accounts are omitted from the sum, not treated as zero. */
  public record Point(LocalDate date, BigDecimal value, boolean estimated) {}

  /** Percent is unavailable for a zero starting value; flows are in the response currency. */
  public record Change(
      BigDecimal amount,
      @Nullable BigDecimal percent,
      @Nullable BigDecimal contributions,
      @Nullable BigDecimal marketAndFx,
      @Nullable BigDecimal capitalMovements) {

    public Change(
        BigDecimal amount,
        @Nullable BigDecimal percent,
        @Nullable BigDecimal contributions,
        @Nullable BigDecimal marketAndFx) {
      this(amount, percent, contributions, marketAndFx, null);
    }
  }

  /** Only workspace-wide history includes a per-section period change. */
  public record SectionChange(PortfolioSection section, Change change) {}
}
