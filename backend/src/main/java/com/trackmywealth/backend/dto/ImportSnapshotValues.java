package com.trackmywealth.backend.dto;

/**
 * US-07-31 (#366): how a statement meets the imported snapshot of its account and date. The
 * snapshot has two parts, its balance and its holdings; each part the statement holds is added
 * where the snapshot lacks it, kept where both agree, and a conflict where they differ. Nothing is
 * ever overwritten.
 */
public final class ImportSnapshotValues {

  /** No imported snapshot of the date yet: the commit records one. */
  public static final String NEW = "NEW";

  /** The commit adds at least one part to the snapshot, and nothing differs. */
  public static final String COMPLETES = "COMPLETES";

  /** The statement adds nothing and differs in nothing: a duplicate. */
  public static final String ALREADY_RECORDED = "ALREADY_RECORDED";

  /** The statement differs from the snapshot in at least one part. */
  public static final String CONFLICTING = "CONFLICTING";

  /** The account's dates do not admit the statement's. */
  public static final String OUT_OF_RANGE = "OUT_OF_RANGE";

  /** A part: the snapshot's balance. */
  public static final String PART_BALANCE = "BALANCE";

  /** A part: the snapshot's holdings (in a conflict, {@code HOLDING} names one position). */
  public static final String PART_HOLDINGS = "HOLDINGS";

  public static final String CONFLICT_HOLDING = "HOLDING";

  /** After the commit: the batch recorded the snapshot. */
  public static final String RECORDED = ImportBatchValues.SNAPSHOT_RECORDED;

  /** After the commit: the batch added parts to a snapshot another batch recorded. */
  public static final String COMPLETED = "COMPLETED";

  /** After a rollback: the batch's parts were taken out; the snapshot stays with another's. */
  public static final String REMOVED = "REMOVED";

  private ImportSnapshotValues() {}
}
