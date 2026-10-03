package com.trackmywealth.backend.dto;

/** Stable API values describing an account's cash reconciliation state (US-25-02). */
public final class ReconciliationStatusValues {

  private ReconciliationStatusValues() {}

  public static final String NEVER = "NEVER";
  public static final String RECONCILED = "RECONCILED";
  public static final String OPEN_DIFFERENCE = "OPEN_DIFFERENCE";
  public static final String NOT_RECONCILABLE = "NOT_RECONCILABLE";

  /** No opening balance exists at or before the snapshot date, so no ledger baseline is known. */
  public static final String NO_OPENING_BALANCE = "NO_OPENING_BALANCE";

  /** This first reconciliation slice handles transaction-backed, non-position accounts only. */
  public static final String CASH_SCOPE_NOT_APPLICABLE = "CASH_SCOPE_NOT_APPLICABLE";
}
