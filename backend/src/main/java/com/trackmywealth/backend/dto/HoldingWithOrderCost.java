package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * US-15-15 (#714): a statement's position as {@code holding} reports it, with the cost basis its
 * orders give in place of the one it lacks. Its cost in the account currency stays unknown unless
 * the cost basis is in it (no FX is applied).
 */
public record HoldingWithOrderCost(ReportedHoldingValue holding, CostFromOrders cost)
    implements ReportedHoldingValue {

  @Override
  public UUID getAccountId() {
    return holding.getAccountId();
  }

  @Override
  public LocalDate getSnapshotDate() {
    return holding.getSnapshotDate();
  }

  @Override
  public boolean isUnrecordedCash() {
    return holding.isUnrecordedCash();
  }

  @Override
  public @Nullable BigDecimal getReportedValue() {
    return holding.getReportedValue();
  }

  @Override
  public @Nullable String getReportedCurrency() {
    return holding.getReportedCurrency();
  }

  @Override
  public boolean isHolding() {
    return true;
  }

  @Override
  public @Nullable UUID getSecurityId() {
    return holding.getSecurityId();
  }

  @Override
  public @Nullable BigDecimal getQuantity() {
    return holding.getQuantity();
  }

  @Override
  public BigDecimal getReportedCostBasis() {
    return cost.costBasis();
  }

  @Override
  public String getReportedCostBasisCurrency() {
    return cost.currency();
  }

  @Override
  public @Nullable BigDecimal getCostBasisInAccountCurrency() {
    return null;
  }
}
