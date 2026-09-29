package com.trackmywealth.backend.dto;

/**
 * How a transaction is removed (US-07-02, FR-LIF-002a/002b) - decided by the system from its
 * provenance, never chosen by the member. Shown on every {@link TransactionResponse} ({@code
 * removal}) so a client knows in advance whether to ask for a reason.
 */
public final class TransactionRemovalValues {

  /** T1: a manually entered row is hidden, restorable for 30 days, never purged. */
  public static final String SOFT_DELETE = "SOFT_DELETE";

  /** T2: an imported row is voided with a reason and reversed by a new row. */
  public static final String VOID = "VOID";

  private TransactionRemovalValues() {}
}
