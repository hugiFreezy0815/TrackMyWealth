package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "snapshot_foreign_cash")
public class SnapshotForeignCash {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID workspaceId;

  @Column(nullable = false)
  private UUID snapshotId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  @Column(nullable = false)
  private BigDecimal amount;

  private UUID importBatchId;

  protected SnapshotForeignCash() {}

  public SnapshotForeignCash(
      AccountSnapshot snapshot, String currency, BigDecimal amount, UUID importBatchId) {
    this.workspaceId = snapshot.getWorkspace().getId();
    this.snapshotId = snapshot.getId();
    this.currency = currency;
    this.amount = amount;
    this.importBatchId = importBatchId;
  }

  public UUID getSnapshotId() {
    return snapshotId;
  }

  public String getCurrency() {
    return currency;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public UUID getImportBatchId() {
    return importBatchId;
  }
}
