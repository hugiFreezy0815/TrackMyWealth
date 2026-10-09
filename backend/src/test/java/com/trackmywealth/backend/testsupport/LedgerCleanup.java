package com.trackmywealth.backend.testsupport;

/**
 * Empties the ledger between tests. V68's {@code trg_transaction_no_hard_delete} refuses every
 * {@code DELETE FROM transaction} except an import batch's rollback, and {@code
 * trg_transaction_no_truncate} every {@code TRUNCATE} that reaches the table (a {@code CASCADE}
 * from {@code workspace} included) - a test's cleanup too, which is exactly what {@code
 * ImportRollbackControllerTest} proves. Cleanup therefore runs its one statement with triggers off
 * ({@code session_replication_role = replica}, which the tests' superuser may set) and sets the
 * role back right after it, so neither a pooled connection nor a surrounding transaction keeps it.
 * Foreign keys are triggers too, so a {@code DELETE} does not check them: delete the rows that
 * reference transactions first, as every cleanup list already does.
 */
public final class LedgerCleanup {

  /** One statement: removes every transaction, whatever references it. */
  public static final String DELETE_ALL_TRANSACTIONS = withGuardsOff("DELETE FROM transaction");

  private LedgerCleanup() {}

  /** {@code statement} (no {@code $$} inside) as one statement run with triggers off. */
  public static String withGuardsOff(String statement) {
    return "DO $$ DECLARE previous TEXT := current_setting('session_replication_role'); BEGIN"
        + " PERFORM set_config('session_replication_role', 'replica', TRUE); "
        + statement
        + "; PERFORM set_config('session_replication_role', previous, TRUE); END $$";
  }
}
