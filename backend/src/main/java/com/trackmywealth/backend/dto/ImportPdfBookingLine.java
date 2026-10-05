package com.trackmywealth.backend.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One booking line of a PDF statement (#268): a line the template's record-start pattern finds.
 * When its row pattern matches too, {@code cells} are the pattern's capture groups, one per layout
 * column, then the words under each header label and the section's value, per {@link
 * ImportPdfLayout#cellNames()}. When it does not, the line is still kept, never dropped: {@code
 * matched} is false and {@code cells} holds the whole line, which the parser reports as {@link
 * ImportRowErrorValues#LINE_UNMATCHED}.
 *
 * @param continuation the lines after it that were appended to the layout's continuation column
 * @param sectionStart whether it is the first booking of a section: no balance before it carries on
 * @param statedBalance the balance a balance line stated since the booking before it, as written;
 *     {@code null} for none
 * @param balancesAfter the balance lines below it, before the next booking of its section: each
 *     must state the balance it leads to
 * @param headed whether it was read under a header line of the layout's header labels
 */
public record ImportPdfBookingLine(
    String line,
    List<String> cells,
    boolean matched,
    List<String> continuation,
    boolean sectionStart,
    String statedBalance,
    List<BalanceLine> balancesAfter,
    boolean headed) {

  /**
   * A balance line below a booking (e.g. a section's closing balance).
   *
   * @param line the line as read
   * @param balance the balance it states, as written
   */
  public record BalanceLine(String line, String balance) {}

  public ImportPdfBookingLine {
    cells = Collections.unmodifiableList(new ArrayList<>(cells));
    continuation = List.copyOf(continuation);
    balancesAfter = List.copyOf(balancesAfter);
  }

  /** A matched line without anything around it: no continuation, section or stated balance. */
  public ImportPdfBookingLine(String line, List<String> cells, boolean matched) {
    this(line, cells, matched, List.of(), false, null, List.of(), false);
  }

  /** A line the row pattern does not match. */
  public static ImportPdfBookingLine unmatched(String line) {
    return new ImportPdfBookingLine(line, List.of(line), false);
  }

  /** The line as read, with its continuation lines below it: what the row's raw data keeps. */
  public String fullText() {
    if (continuation.isEmpty()) {
      return line;
    }
    return line + "\n" + String.join("\n", continuation);
  }
}
