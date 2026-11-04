package com.trackmywealth.backend.error;

/**
 * 409 {@link ProblemCode#RECONCILIATION_FINALIZED} (US-25-03): a newer snapshot has finalized this
 * accepted or dismissed result, so it can no longer be reopened or decided on again. {@code status}
 * is the decision that stands. Unlike {@link ReconciliationStaleException}, reloading does not
 * help: a correction goes into the newest comparison.
 */
public class ReconciliationFinalizedException extends ApiException {

  private static final long serialVersionUID = 1L;

  public ReconciliationFinalizedException(String status) {
    super(
        ProblemCode.RECONCILIATION_FINALIZED,
        "A newer snapshot has finalized this reconciliation decision, so it can no longer be"
            + " reopened. Correct the difference in the latest reconciliation instead.");
    getBody().setProperty("status", status);
  }
}
