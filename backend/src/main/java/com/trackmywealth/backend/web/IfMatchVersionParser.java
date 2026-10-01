package com.trackmywealth.backend.web;

import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Parses the strong entity-tag form used by FR-CNC-001/002 optimistic concurrency.
 *
 * <p>TrackMyWealth publishes row versions as {@code ETag: "<version>"}. State-changing requests
 * must send that exact value in {@code If-Match}. Weak tags, wildcard tags and unquoted integers
 * are rejected: the application needs one specific row version, not cache-style fuzzy matching.
 */
public final class IfMatchVersionParser {

  public static final String HEADER = "If-Match";

  // Integer.MAX_VALUE has 10 digits; anything longer cannot be a version, and stays within long.
  private static final int MAX_VERSION_DIGITS = 10;

  private IfMatchVersionParser() {}

  public static Integer parse(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank()) {
      return null;
    }

    String value = ifMatch.strip();
    if (value.length() < 3 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
      throw invalid();
    }

    String version = value.substring(1, value.length() - 1);
    // ASCII digits only: Character.isDigit (and Integer.parseInt) would also accept e.g.
    // Arabic-Indic digits, which no ETag this API publishes ever contains.
    if (version.isEmpty()
        || version.length() > MAX_VERSION_DIGITS
        || !version.chars().allMatch(c -> c >= '0' && c <= '9')) {
      throw invalid();
    }
    long parsed = Long.parseLong(version);
    if (parsed > Integer.MAX_VALUE) {
      throw invalid();
    }
    return (int) parsed;
  }

  public static String toEtag(int version) {
    return "\"" + version + "\"";
  }

  private static ApiException invalid() {
    return new ApiException(
        HttpStatus.BAD_REQUEST,
        ApiErrorCode.VALIDATION_FAILED,
        "If-Match must be a strong numeric ETag such as \"7\".");
  }
}
