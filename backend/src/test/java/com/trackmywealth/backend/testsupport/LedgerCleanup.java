package com.trackmywealth.backend.testsupport;

/**
 * Empties the ledger between tests. V68's {@code trg_transaction_no_hard_delete} refuses every
 * {@code DELETE FROM transaction} except an import batch's rollback - a test's cleanup included,
 * which is exactly what {@code ImportRollbackControllerTest} proves. Cleanup therefore deletes with
 * triggers off ({@code session_replication_role = replica}, which the tests' superuser may set) for
 * the one statement's own transaction only, so no pooled connection keeps it. Foreign keys are
 * triggers too, so they are not checked either: delete the rows that reference transactions first,
 * as every cleanup list already does.
 */
public final class LedgerCleanup {

  /** One statement: removes every transaction, whatever references it. */
  public static final String DELETE_ALL_TRANSACTIONS =
      "DO $$ BEGIN PERFORM set_config('session_replication_role', 'replica', TRUE);"
          + " DELETE FROM transaction; END $$";

  private LedgerCleanup() {}
}
