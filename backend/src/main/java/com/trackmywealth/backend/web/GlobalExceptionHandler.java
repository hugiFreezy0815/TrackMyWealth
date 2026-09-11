package com.trackmywealth.backend.web;

import org.springframework.dao.DataIntegrityViolationException;
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
 * (V4's {@code trg_account_type_immutable}, V24's {@code trg_account_currency_immutable}) so the
 * response can name the actual requirement violated; any other integrity violation still gets a
 * clean, generic 409 rather than a 500, but without echoing the raw DB message to the caller.
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
    return conflict("The request conflicts with an existing data constraint.");
  }

  private ProblemDetail conflict(String detail) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
  }

  private String rootCauseMessage(Throwable throwable) {
    Throwable root = throwable;
    // Throwable's own cause-chain convention: a Throwable with no explicit cause has its cause
    // set to itself (see Throwable(String) javadoc), so equals() (Object's default, i.e. identity
    // for a class that doesn't override it) is the correct loop guard here, not just a style
    // preference - this stops at the first self-referencing cause instead of never terminating.
    while (root.getCause() != null && !root.getCause().equals(root)) {
      root = root.getCause();
    }
    return root.getMessage() == null ? "" : root.getMessage();
  }
}
