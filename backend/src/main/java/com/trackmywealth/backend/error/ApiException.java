package com.trackmywealth.backend.error;

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

  public ApiException(HttpStatusCode status, String code, String reason) {
    super(status, reason);
    this.code = code;
    getBody().setProperty(ApiErrorCode.PROPERTY, code);
  }

  public String getCode() {
    return code;
  }
}
