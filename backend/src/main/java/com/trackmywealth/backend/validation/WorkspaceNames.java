package com.trackmywealth.backend.validation;

import java.util.Set;

/** One normalisation and validation policy for first-run setup and subsequent name changes. */
public final class WorkspaceNames {

  private static final int MAX_LENGTH = 60;
  // Format characters that legitimate text needs: emoji sequences (a family, a rainbow flag) are
  // joined by U+200D, and Persian or Indic spelling uses U+200C to keep letters apart.
  private static final int ZERO_WIDTH_NON_JOINER = 0x200C;
  private static final int ZERO_WIDTH_JOINER = 0x200D;
  // Letters and symbols that render as nothing: the Hangul fillers and the blank braille pattern.
  // A name made of them would look empty but bypass the "All portfolios" fallback.
  private static final Set<Integer> BLANK_LOOKING = Set.of(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800);

  private WorkspaceNames() {}

  /**
   * Null and blank names mean "no name"; spaces around a name are removed, including no-break
   * spaces, which {@link String#strip()} keeps.
   */
  public static String normalize(String value) {
    if (value == null) {
      return null;
    }
    int start = 0;
    int end = value.length();
    while (start < end && isSpace(value.codePointAt(start))) {
      start += Character.charCount(value.codePointAt(start));
    }
    while (end > start && isSpace(value.codePointBefore(end))) {
      end -= Character.charCount(value.codePointBefore(end));
    }
    return start == end ? null : value.substring(start, end);
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

  private static boolean isSpace(int codePoint) {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
  }

  // Controls, line breaks, bidi overrides and other invisible format characters can be deceptive
  // in UI labels; only the two joiners above are kept. A lone surrogate is not text at all: the
  // database driver would store it as "?". Check the original, not only the trimmed
  // representation.
  private static boolean isUnsafeCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    if (type == Character.FORMAT) {
      return codePoint != ZERO_WIDTH_NON_JOINER && codePoint != ZERO_WIDTH_JOINER;
    }
    return Character.isISOControl(codePoint)
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR
        || type == Character.SURROGATE
        || BLANK_LOOKING.contains(codePoint);
  }
}
