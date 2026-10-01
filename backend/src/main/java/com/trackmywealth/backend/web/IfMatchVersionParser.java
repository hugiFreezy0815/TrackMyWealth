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

  private IfMatchVersionParser() {}

  public static int parseRequired(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank()) {
      throw new ApiException(
          HttpStatus.PRECONDITION_REQUIRED,
          ApiErrorCode.VERSION_REQUIRED,
          "This update requires If-Match with the version you last read.");
    }

    String value = ifMatch.strip();
    if (value.length() < 3 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
      throw invalid();
    }

    String version = value.substring(1, value.length() - 1);
    if (version.isEmpty() || !version.chars().allMatch(Character::isDigit)) {
      throw invalid();
    }

    try {
      return Integer.parseInt(version);
    } catch (NumberFormatException ex) {
      throw invalid();
    }
  }

  public static String toEtag(int version) {
    return """ + version + """;
  }

  private static ApiException invalid() {
    return new ApiException(
        HttpStatus.BAD_REQUEST,
        ApiErrorCode.VALIDATION_FAILED,
        "If-Match must be a strong numeric ETag such as \"7\".");
  }
}
