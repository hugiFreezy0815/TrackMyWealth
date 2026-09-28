package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maps {@code snapshot_holding} (V11, V33): one reported position of an {@link AccountSnapshot}. At
 * most one line per security per snapshot (V33). {@code reportedCostBasis} is the total cost in the
 * snapshot's currency (the account's native currency), and may be missing: transferred-in positions
 * often arrive without one (FR-REC-008).
 *
 * <p>Not RLS-protected itself (V20): isolation is inherited through {@code snapshot_id}, and it is
 * only ever read by the id of a snapshot already loaded under the caller's workspace. The ids are
 * plain columns rather than associations for the same reason - nothing navigates from a holding.
 */
@Entity
@Table(name = "snapshot_holding")
public class SnapshotHolding {

  private static final String UUID_COLUMN = "uuid";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = UUID_COLUMN)
  private UUID id;

  @Column(name = "snapshot_id", columnDefinition = UUID_COLUMN, nullable = false)
  private UUID snapshotId;

  @Column(name = "security_id", columnDefinition = UUID_COLUMN, nullable = false)
  private UUID securityId;

  @Column(nullable = false)
  private BigDecimal quantity;

  @Column(name = "reported_cost_basis")
  private BigDecimal reportedCostBasis;

  @Column(name = "cost_basis_is_estimated", nullable = false)
  private boolean costBasisEstimated;

  public UUID getId() {
    return id;
  }

  public UUID getSnapshotId() {
    return snapshotId;
  }

  public void setSnapshotId(UUID snapshotId) {
    this.snapshotId = snapshotId;
  }

  public UUID getSecurityId() {
    return securityId;
  }

  public void setSecurityId(UUID securityId) {
    this.securityId = securityId;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public void setQuantity(BigDecimal quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getReportedCostBasis() {
    return reportedCostBasis;
  }

  public void setReportedCostBasis(BigDecimal reportedCostBasis) {
    this.reportedCostBasis = reportedCostBasis;
  }

  public boolean isCostBasisEstimated() {
    return costBasisEstimated;
  }

  public void setCostBasisEstimated(boolean costBasisEstimated) {
    this.costBasisEstimated = costBasisEstimated;
  }
}
