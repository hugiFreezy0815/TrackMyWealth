package com.trackmywealth.backend.validation;

/** One normalisation and validation policy for first-run setup and subsequent name changes. */
public final class WorkspaceNames {

  private static final int MAX_LENGTH = 60;
  // Format characters that legitimate text needs: emoji sequences (a family, a rainbow flag) are
  // joined by U+200D, and Persian or Indic spelling uses U+200C to keep letters apart.
  private static final int ZERO_WIDTH_NON_JOINER = 0x200C;
  private static final int ZERO_WIDTH_JOINER = 0x200D;

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
    return normalized == null || normalized.codePointCount(0, normalized.length()) <= MAX_LENGTH;
  }

  // Controls, line breaks, bidi overrides and other invisible format characters can be deceptive
  // in UI labels; only the two joiners above are kept. Check the original, not only the trimmed
  // representation.
  private static boolean isUnsafeCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    if (type == Character.FORMAT) {
      return codePoint != ZERO_WIDTH_NON_JOINER && codePoint != ZERO_WIDTH_JOINER;
    }
    return Character.isISOControl(codePoint)
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }
}
