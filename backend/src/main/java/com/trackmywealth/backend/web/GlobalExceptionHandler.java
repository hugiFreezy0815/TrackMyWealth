package com.trackmywealth.backend.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates persistence-layer failures the service layer doesn't itself anticipate into the same
 * kind of structured, non-leaking response a hand-thrown {@code ResponseStatusException} already
 * produces - without this, a rejected {@code DataIntegrityViolationException} (e.g. a BEFORE UPDATE
 * trigger's {@code RAISE EXCEPTION}, SQLSTATE class 23) would otherwise reach the caller as a bare
 * 500 with an internal stack trace/SQL message potentially attached (US-05-02's Definition of
 * Done).
 *
 * <p>Recognizes specific immutability-guard triggers by the distinct message prefix each one raises
 * (V4's {@code trg_account_type_immutable}, V24's {@code trg_account_currency_immutable}, V26's
 * {@code trg_custom_asset_valuation_currency_guard}) so the response can name the actual
 * requirement violated; any other integrity violation - including V5/V26's generic {@code
 * trg_extension_type_guard} reuse - still gets a clean, generic 409 rather than a 500, but without
 * echoing the raw DB message to the caller.
 *
 * <p>Also handles {@link OptimisticLockingFailureException}: every entity in this codebase carries
 * a real {@code @Version} column (see {@code AppUserRepository}'s own comment on the same pitfall),
 * so any read-modify-{@code saveAndFlush} write path - {@code AccountService.updateAccount} being
 * the first one to do a genuine read-modify-write rather than an atomic UPDATE or a fresh INSERT -
 * can lose this race and must not surface it as an unhandled 500 either.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
    String rootMessage = rootCauseMessage(ex);

    if (rootMessage.contains("account_type_immutable")) {
      return conflict(
          "An account's type cannot be changed after creation (FR-ACC-005/G5). To convert this"
              + " account, archive it and create a new one with the correct type.");
    }
    if (rootMessage.contains("account_currency_immutable")) {
      return conflict(
          "An account's currency cannot be changed after creation (FR-ACC-002). To convert this"
              + " account, archive it and create a new one with the correct currency.");
    }
    if (rootMessage.contains("custom_asset_valuation_currency_mismatch")) {
      return conflict("A valuation's currency must match the account's own currency (FR-ACC-002).");
    }
    if (rootMessage.contains("account_snapshot_currency_mismatch")) {
      return conflict("A snapshot's currency must match the account's own currency (FR-ACC-002).");
    }
    // Two concurrent entries for the same account and date: the loser of the race lands here
    // rather than on AccountSnapshotService's own existence check, which answers with the id.
    if (rootMessage.contains("account_snapshot_account_id_snapshot_date_source_key")) {
      return conflict(
          "A snapshot for this account and date already exists. Retry the request to get its id,"
              + " then update it instead.");
    }
    if (rootMessage.contains("uq_transaction_external_id")) {
      return conflict(
          "A transaction with this externalId is already being recorded. Retry the request: it"
              + " returns the recorded transaction.");
    }
    if (rootMessage.contains("uq_category_workspace_code")) {
      return conflict(
          "A category with the same name was created at the same moment. Retry the request.");
    }
    return conflict("The request conflicts with an existing data constraint.");
  }

  @ExceptionHandler(ExistingResourceConflictException.class)
  public ProblemDetail handleExistingResourceConflict(ExistingResourceConflictException ex) {
    ProblemDetail problem = conflict(ex.getMessage());
    problem.setProperty(ex.getPropertyName(), ex.getExistingId());
    return problem;
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ProblemDetail handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
    return conflict(
        "This record was changed by another request in the meantime. Reload it and retry your"
            + " update.");
  }

  private ProblemDetail conflict(String detail) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
  }

  private String rootCauseMessage(Throwable throwable) {
    Throwable root = throwable;
    // Throwable.getCause() is specified to return null, never the throwable itself, when no
    // cause was set (the self-reference Throwable uses internally to mark "no cause yet" is a
    // private implementation detail getCause() already unwraps) - so a plain null check
    // terminates this loop with no separate self-reference guard needed.
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root.getMessage() == null ? "" : root.getMessage();
  }
}
