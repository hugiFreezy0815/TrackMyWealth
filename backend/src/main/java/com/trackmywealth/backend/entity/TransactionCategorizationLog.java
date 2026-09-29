package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Maps {@code transaction_categorization_log} (V13): one row per category assignment, the latest of
 * which says how a transaction got its current category (FR-CAT-002/006). Written once, never
 * updated. It carries no {@code workspace_id}; it is only ever read by the id of a transaction
 * already loaded under that table's RLS policy.
 */
@Entity
@Table(name = "transaction_categorization_log")
public class TransactionCategorizationLog {

  private static final String UUID_COLUMN = "uuid";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = UUID_COLUMN)
  private UUID id;

  @Column(
      name = "transaction_id",
      nullable = false,
      updatable = false,
      columnDefinition = UUID_COLUMN)
  private UUID transactionId;

  @Column(name = "category_id", nullable = false, updatable = false, columnDefinition = UUID_COLUMN)
  private UUID categoryId;

  // SOURCE_CODE, RULE, TRANSACTION_TYPE, FALLBACK_MATCH or USER (V13, V37 CHECK).
  @Column(name = "assigned_by", nullable = false, updatable = false)
  private String assignedBy;

  @Column(name = "rule_id", updatable = false, columnDefinition = UUID_COLUMN)
  private UUID ruleId;

  // FALLBACK_MATCH only: the similarity the match was made on, 0..1.
  @Column(precision = 4, scale = 3, updatable = false)
  private BigDecimal confidence;

  // US-08-02: a member's own choice, which no automatic run may replace (RULE-031, FR-CAT-014).
  @Column(name = "is_user_override", nullable = false, updatable = false)
  private boolean userOverride;

  @Generated(event = EventType.INSERT)
  @Column(name = "assigned_at", insertable = false, updatable = false)
  private OffsetDateTime assignedAt;

  protected TransactionCategorizationLog() {}

  /** An automatic assignment. */
  public TransactionCategorizationLog(
      UUID transactionId, UUID categoryId, String assignedBy, UUID ruleId, BigDecimal confidence) {
    this.transactionId = transactionId;
    this.categoryId = categoryId;
    this.assignedBy = assignedBy;
    this.ruleId = ruleId;
    this.confidence = confidence;
  }

  /** A member's override (US-08-02): {@code USER}, protected from every automatic run. */
  public static TransactionCategorizationLog ofUserOverride(UUID transactionId, UUID categoryId) {
    TransactionCategorizationLog log =
        new TransactionCategorizationLog(transactionId, categoryId, "USER", null, null);
    log.userOverride = true;
    return log;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTransactionId() {
    return transactionId;
  }

  public UUID getCategoryId() {
    return categoryId;
  }

  public String getAssignedBy() {
    return assignedBy;
  }

  public UUID getRuleId() {
    return ruleId;
  }

  public boolean isUserOverride() {
    return userOverride;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }
}
