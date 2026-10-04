package com.trackmywealth.backend.error;

import org.springframework.http.HttpStatus;

/**
 * 422: an import file cannot be read with the chosen template at all (US-07-03) - its header lacks
 * mapped columns, it is empty, malformed, in another encoding or too long. Its {@code IMPORT_*}
 * code tells which; no row of it was parsed.
 */
public class ImportFileRejectedException extends ApiException {

  private static final long serialVersionUID = 1L;

  public ImportFileRejectedException(String code, String detail) {
    super(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
  }

  public ImportFileRejectedException(String code, String detail, Throwable cause) {
    super(HttpStatus.UNPROCESSABLE_CONTENT, code, detail, cause);
  }

  /** Adds a property to the problem body, e.g. {@code missingColumns}. */
  public ImportFileRejectedException withProperty(String name, Object value) {
    getBody().setProperty(name, value);
    return this;
  }
}
