package com.trackmywealth.backend.pdf;

/**
 * One word of a text line, with where it sits on the page (#268): from the left edge of its first
 * character to the right edge of its last, in the page's text space. A statement's columns are
 * found by these positions, since an unsigned amount says only by its column whether it is a debit
 * or a credit.
 */
public record PdfWord(String text, float left, float right) {

  /** How far this word overlaps {@code [from, to]} horizontally; zero when it does not. */
  public float overlap(float from, float to) {
    return Math.max(0, Math.min(right, to) - Math.max(left, from));
  }

  /** How far this word is from {@code [from, to]} horizontally; zero when it overlaps it. */
  public float distance(float from, float to) {
    return Math.max(0, Math.max(from - right, left - to));
  }
}
