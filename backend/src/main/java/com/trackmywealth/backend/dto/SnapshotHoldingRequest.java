package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One reported position of a snapshot (US-25-01). {@code securityId} is a security-master id: a
 * security not yet in the master is created first through {@code POST /api/v1/securities}
 * (US-12-01), which stays the only way to add to the shared master.
 *
 * <p>{@code reportedCostBasis} is the total cost as printed on the statement, in the account's own
 * currency; leave it out when the statement has none (FR-REC-008). {@code costBasisIsEstimated}
 * marks a figure the user worked out rather than copied (PR-011) and defaults to {@code false}. The
 * statement's market value and currency are optional and supplied together. A unit price may
 * accompany the market value.
 */
public record SnapshotHoldingRequest(
    @NotNull UUID securityId,
    @NotNull @Positive @Digits(integer = 18, fraction = 10) BigDecimal quantity,
    @PositiveOrZero @Digits(integer = 16, fraction = 4) BigDecimal reportedCostBasis,
    Boolean costBasisIsEstimated,
    @PositiveOrZero @Digits(integer = 10, fraction = 10) BigDecimal reportedPrice,
    @PositiveOrZero @Digits(integer = 16, fraction = 4) BigDecimal reportedValue,
    @ValidCurrencyCode String reportedCurrency) {

  public SnapshotHoldingRequest(
      UUID securityId, BigDecimal quantity, BigDecimal reportedCostBasis, Boolean estimated) {
    this(securityId, quantity, reportedCostBasis, estimated, null, null, null);
  }
}
