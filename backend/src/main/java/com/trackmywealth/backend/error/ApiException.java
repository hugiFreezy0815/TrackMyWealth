package com.trackmywealth.backend.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * A {@link ResponseStatusException} with a specific {@link ProblemCode} - for the errors a client
 * must tell apart from others of the same status (e.g. {@code ACCOUNT_ARCHIVED} among 409s). Every
 * other rule keeps throwing a plain {@code ResponseStatusException}, which gets its status's
 * class-level code ({@link ProblemCode#forStatus}).
 *
 * <p>The status comes from the code ({@link ProblemCode#status()}); the few codes answered with
 * more than one status take it explicitly, and a status the code does not list is refused, so the
 * catalogue (US-29-04) stays true.
 */
public class ApiException extends ResponseStatusException {

  private static final long serialVersionUID = 1L;

  private final ProblemCode code;
  // Seconds a client should wait before retrying; 0 sends no Retry-After header.
  private long retryAfterSeconds;

  // A code always allows its own status, so these two need no check.
  public ApiException(ProblemCode code, String reason) {
    super(code.status(), reason);
    this.code = code;
    getBody().setProperty(ProblemCode.PROPERTY, code.name());
  }

  /** As above, keeping the exception that caused it for the log. */
  public ApiException(ProblemCode code, String reason, Throwable cause) {
    super(code.status(), reason, cause);
    this.code = code;
    getBody().setProperty(ProblemCode.PROPERTY, code.name());
  }

  /** For a code answered with more than one status (e.g. {@code VERSION_CONFLICT}: 412 or 409). */
  public ApiException(HttpStatusCode status, ProblemCode code, String reason) {
    super(requireAllowed(status, code), reason);
    this.code = code;
    getBody().setProperty(ProblemCode.PROPERTY, code.name());
  }

  /** As above, keeping the exception that caused it for the log. */
  public ApiException(HttpStatusCode status, ProblemCode code, String reason, Throwable cause) {
    super(requireAllowed(status, code), reason, cause);
    this.code = code;
    getBody().setProperty(ProblemCode.PROPERTY, code.name());
  }

  public ProblemCode getCode() {
    return code;
  }

  /**
   * Adds a {@code Retry-After} header, for an error the client may retry unchanged once the server
   * has capacity again (e.g. a 503 for a busy server).
   */
  public ApiException withRetryAfter(long seconds) {
    this.retryAfterSeconds = seconds;
    return this;
  }

  @Override
  public HttpHeaders getHeaders() {
    if (retryAfterSeconds <= 0) {
      return super.getHeaders();
    }
    HttpHeaders headers = new HttpHeaders();
    headers.addAll(super.getHeaders());
    headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
    return headers;
  }

  private static HttpStatusCode requireAllowed(HttpStatusCode status, ProblemCode code) {
    if (!code.allows(status)) {
      throw new IllegalArgumentException(
          code + " is answered with " + code.statuses() + ", never with " + status.value());
    }
    return status;
  }
}
