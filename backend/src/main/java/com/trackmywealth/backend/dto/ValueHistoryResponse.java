package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * US-11-06: server-calculated history for net worth or one portfolio, never client-derived. {@code
 * warnings} are account-specific sentences for support; {@code warningCodes} are their stable,
 * de-duplicated codes ({@link ValueHistoryWarningValues}, {@link DataQualityWarningValues}) that a
 * client localises.
 */
public record ValueHistoryResponse(
    String currency,
    @Schema(allowableValues = {"1W", "1M", "YTD", "1Y", "MAX"}) String period,
    LocalDate from,
    LocalDate to,
    List<Point> points,
    Change change,
    List<SectionChange> sections,
    boolean complete,
    List<String> warnings,
    @ArraySchema(
            schema =
                @Schema(
                    allowableValues = {
                      ValueHistoryWarningValues.VALUE_UNKNOWN,
                      ValueHistoryWarningValues.INCEPTION_UNCERTAIN,
                      ValueHistoryWarningValues.LIFECYCLE_UNVERIFIED,
                      ValueHistoryWarningValues.ATTRIBUTION_PENDING_REVIEW,
                      ValueHistoryWarningValues.ATTRIBUTION_FX_UNAVAILABLE,
                      ValueHistoryWarningValues.CAPITAL_MOVEMENTS_UNAVAILABLE,
                      DataQualityWarningValues.TRANSACTIONS_BEFORE_OPENING_BALANCE,
                      DataQualityWarningValues.BOOKED_BEFORE_OPENING_BALANCE,
                      DataQualityWarningValues.OPEN_RECONCILIATION_DIFFERENCE
                    }))
        List<String> warningCodes) {

  public ValueHistoryResponse {
    points = List.copyOf(points);
    sections = List.copyOf(sections);
    warnings = List.copyOf(warnings);
    warningCodes = List.copyOf(warningCodes);
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
