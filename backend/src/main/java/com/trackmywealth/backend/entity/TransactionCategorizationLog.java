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
import org.jspecify.annotations.Nullable;

/**
 * Maps {@code transaction_categorization_log} (V13): one row per category assignment, the latest of
 * which says how a transaction got its current category (FR-CAT-002/006). Written once, never
 * updated. It carries no {@code workspace_id}; it is only ever read by the id of a transaction
 * already loaded under that table's RLS policy.
 */
@Entity
@Table(name = "transaction_categorization_log")
public class TransactionCategorizationLog {

  /**
   * US-07-38: the {@code assigned_by} of a member's reset whose automatic result assigned nothing
   * (UNCATEGORIZED, or a type the engine never categorizes). Such a row records who reset and when;
   * it never says how the current category was assigned.
   */
  public static final String ASSIGNED_BY_RESET = "RESET";

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

  // NULL only for a reset of a type the engine never categorizes (V117).
  @Column(name = "category_id", updatable = false, columnDefinition = UUID_COLUMN)
  private @Nullable UUID categoryId;

  // SOURCE_CODE, RULE, TRANSACTION_TYPE, FALLBACK_MATCH, USER or RESET (V13, V37, V117 CHECK).
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

  // V38: the member who made an override; NULL for an automatic assignment.
  @Column(name = "assigned_by_user_id", updatable = false, columnDefinition = UUID_COLUMN)
  private UUID assignedByUserId;

  // US-07-38: a reset has an actor but is not an override. Never change an old log row.
  @Column(name = "is_reset", nullable = false, updatable = false)
  private boolean reset;

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

  /**
   * A member's override (US-08-02): {@code USER}, protected from every automatic run, and naming
   * the member who made it (V38).
   */
  public static TransactionCategorizationLog ofUserOverride(
      UUID transactionId, UUID categoryId, UUID userId) {
    TransactionCategorizationLog log =
        new TransactionCategorizationLog(transactionId, categoryId, "USER", null, null);
    log.userOverride = true;
    log.assignedByUserId = userId;
    return log;
  }

  /**
   * A member's reset of their override (US-07-38): the automatic decision the reset selected, with
   * its rule and confidence, so a later automatic run finds it unchanged and writes nothing. Not an
   * override, but naming the member who reset. A decision that assigned nothing is recorded as
   * {@link #ASSIGNED_BY_RESET}.
   */
  public static TransactionCategorizationLog ofMemberReset(
      UUID transactionId,
      @Nullable UUID categoryId,
      @Nullable String assignedBy,
      @Nullable UUID ruleId,
      @Nullable BigDecimal confidence,
      UUID userId) {
    TransactionCategorizationLog log =
        new TransactionCategorizationLog(
            transactionId,
            categoryId,
            assignedBy == null ? ASSIGNED_BY_RESET : assignedBy,
            ruleId,
            confidence);
    log.reset = true;
    log.assignedByUserId = userId;
    return log;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTransactionId() {
    return transactionId;
  }

  public @Nullable UUID getCategoryId() {
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

  public boolean isReset() {
    return reset;
  }

  public UUID getAssignedByUserId() {
    return assignedByUserId;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }
}
