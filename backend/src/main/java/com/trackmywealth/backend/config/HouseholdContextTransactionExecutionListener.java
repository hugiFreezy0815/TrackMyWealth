package com.trackmywealth.backend.config;

import com.trackmywealth.backend.security.HouseholdPrincipal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * US-28-01: sets {@code app.current_household_id} exactly once per database transaction, derived
 * server-side from the authenticated principal - never from a request parameter - so the
 * row-level-security policies in {@code V20__tenancy_row_level_security.sql} have a trustworthy
 * value to check. See {@code docs/architecture/adr/0002-household-context-propagation.md} for why
 * this hooks the transaction lifecycle ({@link TransactionExecutionListener#afterBegin}) rather
 * than an AOP aspect around service methods or a Servlet Filter/HandlerInterceptor.
 *
 * <p>{@code afterBegin} fires after a transaction has started but before the advised method body
 * runs, so this is the first statement executed against the transaction's connection - the same
 * connection every subsequent statement in that transaction uses ({@link
 * DataSourceUtils#getConnection} returns the one JDBC connection {@code JpaTransactionManager}
 * already bound to the current transaction, never a new one). {@code set_config(..., true)}'s
 * {@code is_local = true} then guarantees the value cannot survive past this transaction's commit
 * or rollback, so it can never leak into the next transaction that happens to reuse the same
 * physical connection from the pool.
 *
 * <p>Not registered automatically by Spring Boot - {@link TransactionExecutionListenerRegistrar}
 * wires every {@code TransactionExecutionListener} bean into the auto-configured {@code
 * JpaTransactionManager} at startup, since no autoconfiguration does this on its own.
 */
@Component
public class HouseholdContextTransactionExecutionListener implements TransactionExecutionListener {

  private static final Logger log =
      LoggerFactory.getLogger(HouseholdContextTransactionExecutionListener.class);

  private final DataSource dataSource;

  public HouseholdContextTransactionExecutionListener(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
    if (beginFailure != null) {
      return;
    }
    UUID householdId = resolveHouseholdId();
    if (householdId == null) {
      // No resolvable household context (anonymous caller, or an authenticated principal with no
      // household link at all - e.g. a SYSTEM_ADMINISTRATOR per US-02-05). Left unset: every RLS
      // policy then denies by default (FR-TEN-001..003), which is the intended fail-closed
      // behaviour, not an error condition for this listener to report.
      return;
    }
    setHouseholdContext(householdId);
  }

  private UUID resolveHouseholdId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return null;
    }
    if (authentication.getPrincipal() instanceof HouseholdPrincipal householdPrincipal) {
      return householdPrincipal.householdId();
    }
    return null;
  }

  private void setHouseholdContext(UUID householdId) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('app.current_household_id', ?, true)")) {
      statement.setString(1, householdId.toString());
      statement.execute();
    } catch (SQLException e) {
      // Deliberately swallowed, not rethrown: afterBegin(..., null) - the success path this
      // method is only ever called from - runs after AbstractPlatformTransactionManager has
      // already committed to the transaction being under way (doBegin() succeeded,
      // prepareSynchronization() already ran), with no surrounding try/catch anywhere up the
      // call chain to TransactionAspectSupport.invokeWithinTransaction() that would clean up a
      // listener exception thrown from here. Throwing would leave the connection and
      // TransactionSynchronizationManager's thread-bound "transaction active" state permanently
      // stuck for whatever thread happens to be running this - a pool-poisoning resource leak,
      // not a contained failure. Leaving the household context unset instead means RLS denies by
      // default (FR-TEN-001..003) - the caller gets a wrong-but-safe authorization failure rather
      // than a corrupted worker thread, and the underlying connection problem (if real, not
      // transient) still surfaces normally when the business logic's own query runs next.
      log.warn(
          "Failed to set household context for household {}; RLS will deny by default",
          householdId,
          e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }
}
