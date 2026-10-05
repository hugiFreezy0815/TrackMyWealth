package com.trackmywealth.backend.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One data row of an import file cannot be parsed (US-07-03). Never reaches a client: {@code
 * ImportFileParserService} catches it and reports the row as an {@code ERROR} row with this {@link
 * #getCode() code} (an {@code ImportRowErrorValues} code) and its {@link #getArgs() arguments},
 * then carries on with the next row (FR-IMP-012). Without a stack trace, since a large file may
 * reject thousands of rows.
 */
public class ImportRowRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;
  // Transient: Map is not Serializable, and this exception never leaves the parser anyway.
  private final transient Map<String, String> args;

  public ImportRowRejectedException(String code, Map<String, String> args) {
    this(code, args, null);
  }

  public ImportRowRejectedException(String code, Map<String, String> args, Throwable cause) {
    super(code, cause, false, false);
    this.code = code;
    this.args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
  }

  public String getCode() {
    return code;
  }

  public Map<String, String> getArgs() {
    return args;
  }
}
