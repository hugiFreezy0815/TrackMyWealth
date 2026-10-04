package com.trackmywealth.backend.web;

import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ExistingResourceConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.DisconnectedClientHelper;

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
 *
 * <p>EPIC-29 (#149, #175): this is the single place that shapes every error Spring MVC sees into
 * the one {@link ProblemDetails} shape - status, title, detail, a stable {@link ApiErrorCode} and
 * the correlation id. Extending {@link ResponseEntityExceptionHandler} covers {@code
 * ResponseStatusException} (with its reason as detail, which the default error page used to drop)
 * and Spring's own request errors; the handlers below add what is specific to this application.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final String ERRORS = "errors";

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
    String rootMessage = rootCauseMessage(ex);

    if (rootMessage.contains("account_type_immutable")) {
      return conflict(
          ApiErrorCode.IMMUTABLE_FIELD,
          "An account's type cannot be changed after creation (FR-ACC-005/G5). To convert this"
              + " account, archive it and create a new one with the correct type.");
    }
    if (rootMessage.contains("account_currency_immutable")) {
      return conflict(
          ApiErrorCode.IMMUTABLE_FIELD,
          "An account's currency cannot be changed after creation (FR-ACC-002). To convert this"
              + " account, archive it and create a new one with the correct currency.");
    }
    if (rootMessage.contains("custom_asset_valuation_currency_mismatch")) {
      return conflict(
          ApiErrorCode.CONFLICT,
          "A valuation's currency must match the account's own currency (FR-ACC-002).");
    }
    if (rootMessage.contains("account_snapshot_currency_mismatch")) {
      return conflict(
          ApiErrorCode.CONFLICT,
          "A snapshot's currency must match the account's own currency (FR-ACC-002).");
    }
    // Two concurrent entries for the same account and date: the loser of the race lands here
    // rather than on AccountSnapshotService's own existence check, which answers with the id.
    if (rootMessage.contains("account_snapshot_account_id_snapshot_date_source_key")) {
      return conflict(
          ApiErrorCode.RETRY,
          "A snapshot for this account and date already exists. Retry the request to get its id,"
              + " then update it instead.");
    }
    // US-25-04: two concurrent first opening balances for one account (V58's partial index); the
    // loser's retry gets OpeningBalanceService's 409 naming the winner.
    if (rootMessage.contains("uq_account_snapshot_opening_balance")) {
      return conflict(
          ApiErrorCode.RETRY,
          "An opening balance for this account was recorded at the same moment. Retry the request"
              + " to get its id, then replace it instead.");
    }
    if (rootMessage.contains("uq_transaction_external_id")) {
      return conflict(
          ApiErrorCode.RETRY,
          "A transaction with this externalId is already being recorded. Retry the request: it"
              + " returns the recorded transaction.");
    }
    if (rootMessage.contains("uq_category_workspace_code")) {
      return conflict(
          ApiErrorCode.RETRY,
          "A category with the same name was created at the same moment. Retry the request.");
    }
    if (rootMessage.contains("uq_workspace_category_override")) {
      return conflict(
          ApiErrorCode.RETRY,
          "This category was customised by another request at the same moment. Retry the request.");
    }
    return conflict(
        ApiErrorCode.CONFLICT, "The request conflicts with an existing data constraint.");
  }

  @ExceptionHandler(ExistingResourceConflictException.class)
  public ProblemDetail handleExistingResourceConflict(ExistingResourceConflictException ex) {
    ProblemDetail problem = conflict(ApiErrorCode.CONFLICT, ex.getMessage());
    problem.setProperty(ex.getPropertyName(), ex.getExistingId());
    return problem;
  }

  /**
   * A write that lost a race to a concurrent one (JPA {@code @Version}). With {@code If-Match} the
   * client's precondition failed, so it is the same 412 as a stale version caught before the write
   * (ADR 0004). Without one there was no precondition to fail (RFC 9110), so it stays a 409. The
   * code is {@code VERSION_CONFLICT} either way - that is what a client branches on.
   */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ProblemDetail handleOptimisticLockingFailure(
      OptimisticLockingFailureException ex, WebRequest request) {
    HttpStatus status =
        request.getHeader(HttpHeaders.IF_MATCH) == null
            ? HttpStatus.CONFLICT
            : HttpStatus.PRECONDITION_FAILED;
    return ProblemDetails.of(
        status,
        ApiErrorCode.VERSION_CONFLICT,
        "This record was changed after you read it. Reload the current state and retry explicitly.");
  }

  /**
   * 403 for a denial raised inside the application (the security chain answers its own through
   * {@code SecurityConfig}'s access-denied handler).
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
    return ProblemDetails.of(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, "Not allowed.");
  }

  /**
   * Anything unexpected: a 500 that says nothing about its cause (no exception text, class or trace
   * - FR-API-005), only the correlation id to quote. The cause is logged with that id.
   *
   * <p>A client that went away mid-response (#197) is not an error of this application, and there
   * is nobody left to answer: it is logged at debug, and no body is written.
   */
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex) {
    if (isClientDisconnect(ex)) {
      return null;
    }
    LOG.error("Unexpected error answered with 500", ex);
    return ProblemDetails.of(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ApiErrorCode.INTERNAL,
        "An unexpected error occurred. Quote the correlation id when reporting it.");
  }

  /**
   * The usual way a client disconnect arrives (#197): the client goes away while the body is being
   * written, and the message converter wraps the failed write in an {@link
   * HttpMessageNotWritableException}. That is handled here by the base class, never by {@link
   * #handleUnexpected}, and its default answer is a 500 - so the disconnect check is needed here
   * too. Any other unwritable body stays the base class's 500.
   */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotWritable(
      HttpMessageNotWritableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    if (isClientDisconnect(ex)) {
      return null;
    }
    return super.handleHttpMessageNotWritable(ex, headers, status, request);
  }

  private static boolean isClientDisconnect(Exception ex) {
    if (!DisconnectedClientHelper.isClientDisconnectedException(ex)) {
      return false;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Client disconnected before the response was complete: {}", ex.toString());
    }
    return true;
  }

  /**
   * 413 for an upload over {@code spring.servlet.multipart.max-file-size}: the only uploads are
   * import files (US-07-03/04), so it carries their stable code rather than the class-level one.
   */
  @Override
  protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
      MaxUploadSizeExceededException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        ProblemDetails.of(
            HttpStatus.CONTENT_TOO_LARGE,
            ApiErrorCode.IMPORT_FILE_TOO_LARGE,
            "The file is larger than the 5 MB an import file may have.");
    return handleExceptionInternal(ex, problem, headers, HttpStatus.CONTENT_TOO_LARGE, request);
  }

  /** 400 with one entry per rejected field, so a client can mark each input. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      errors.add(
          Map.of("field", error.getField(), "message", messageOf(error.getDefaultMessage())));
    }
    for (ObjectError error : ex.getBindingResult().getGlobalErrors()) {
      errors.add(
          Map.of("field", error.getObjectName(), "message", messageOf(error.getDefaultMessage())));
    }
    // #153: the field messages are already in the caller's language (Bean Validation interpolates
    // them with the request locale); the detail around them must match, not stay English.
    ProblemDetail problem =
        ProblemDetails.of(
            HttpStatus.BAD_REQUEST,
            ApiErrorCode.VALIDATION_FAILED,
            localized("tmw.problem.validation.detail", "The request is invalid."));
    problem.setProperty(ERRORS, errors);
    return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
  }

  /**
   * Every response Spring MVC's own handling builds (a {@code ResponseStatusException} and its
   * reason as {@code detail} - #175 -, an unreadable body, a missing parameter, an unknown path)
   * gets the class-level {@code code} and the correlation id. A 404's detail is whatever the
   * service said, which is the generic "Not found." for anything another tenant owns (US-28-03).
   */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    if (body instanceof ProblemDetail problem) {
      ProblemDetails.decorate(problem);
    }
    return super.createResponseEntity(body, headers, statusCode, request);
  }

  private String messageOf(String message) {
    return message == null ? localized("tmw.validation.invalid", "is invalid") : message;
  }

  /**
   * The message for {@code code} in the request's locale (RequestLocaleResolver: stored preference,
   * then Accept-Language, then English). The default only applies without a MessageSource, which
   * the Spring context always injects (MessageSourceAware) - a standalone test may not.
   */
  private String localized(String code, String defaultMessage) {
    MessageSource messageSource = getMessageSource();
    return messageSource == null
        ? defaultMessage
        : messageSource.getMessage(code, null, defaultMessage, LocaleContextHolder.getLocale());
  }

  private ProblemDetail conflict(String code, String detail) {
    return ProblemDetails.of(HttpStatus.CONFLICT, code, detail);
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
