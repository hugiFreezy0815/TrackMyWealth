package com.trackmywealth.backend.validation;

/** One normalisation and validation policy for first-run setup and subsequent name changes. */
public final class WorkspaceNames {

  private static final int MAX_LENGTH = 60;

  private WorkspaceNames() {}

  /** Null and whitespace-only names mean "no name"; whitespace around a name is removed. */
  public static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** Reject controls in the original input, even when stripping would have removed them. */
  public static boolean isValid(String value) {
    if (value == null) {
      return true;
    }
    if (value.codePoints().anyMatch(WorkspaceNames::isUnsafeCodePoint)) {
      return false;
    }
    String normalized = normalize(value);
    return normalized == null
        || normalized.codePointCount(0, normalized.length()) <= MAX_LENGTH;
  }

  // Controls, bidi overrides, invisible formatting characters and non-line controls can be
  // deceptive in UI labels. Check the original, not only the trimmed representation.
  private static boolean isUnsafeCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isISOControl(codePoint)
        || type == Character.FORMAT
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }
}
