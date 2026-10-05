package com.trackmywealth.backend.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * A {@link ResponseStatusException} with a specific {@link ApiErrorCode} - for the few errors a
 * client must tell apart from others of the same status (e.g. {@code ACCOUNT_ARCHIVED} among 409s).
 * Every other rule keeps throwing a plain {@code ResponseStatusException}, which gets its status's
 * class-level code.
 */
public class ApiException extends ResponseStatusException {

  private static final long serialVersionUID = 1L;

  private final String code;
  // Seconds a client should wait before retrying; 0 sends no Retry-After header.
  private long retryAfterSeconds;

  public ApiException(HttpStatusCode status, String code, String reason) {
    super(status, reason);
    this.code = code;
    getBody().setProperty(ApiErrorCode.PROPERTY, code);
  }

  /** As above, keeping the exception that caused it for the log. */
  public ApiException(HttpStatusCode status, String code, String reason, Throwable cause) {
    super(status, reason, cause);
    this.code = code;
    getBody().setProperty(ApiErrorCode.PROPERTY, code);
  }

  public String getCode() {
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
}
