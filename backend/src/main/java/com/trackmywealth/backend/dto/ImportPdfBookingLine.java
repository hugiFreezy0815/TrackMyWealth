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
 * @param alsoFoundBy the name of the layout's other pattern that finds the line too, which read it
 *     as its own line: no booking for sure, reported as {@link
 *     ImportRowErrorValues#LINE_AMBIGUOUS}; {@code null} for a booking line
 * @param continuationOverflow whether more lines would have continued it than {@link
 *     ImportPdfLayout#MAX_CONTINUATION_LINES}, reported as {@link
 *     ImportRowErrorValues#CONTINUATION_TOO_LONG}
 */
public record ImportPdfBookingLine(
    String line,
    List<String> cells,
    boolean matched,
    List<String> continuation,
    boolean sectionStart,
    String statedBalance,
    List<BalanceLine> balancesAfter,
    boolean headed,
    String alsoFoundBy,
    boolean continuationOverflow) {

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
    this(line, cells, matched, List.of(), false, null, List.of(), false, null, false);
  }

  /**
   * A booking line with its cells, and nothing around it until the builder's {@code with...}
   * methods add it: each optional component by its name, never by its place among ten.
   */
  public static Builder builder(String line, List<String> cells, boolean matched) {
    return new Builder(line, cells, matched);
  }

  /**
   * A line that {@code otherPattern} finds as well as the record-start pattern (PR #281 review).
   */
  public static ImportPdfBookingLine ambiguous(String line, String otherPattern) {
    return builder(line, List.of(line), false).withAlsoFoundBy(otherPattern).build();
  }

  /** A line the row pattern does not match. */
  public static ImportPdfBookingLine unmatched(String line) {
    return new ImportPdfBookingLine(line, List.of(line), false);
  }

  /** Builds an {@link ImportPdfBookingLine}; each optional component defaults to "none". */
  public static final class Builder {

    private final String bookingLine;
    private final List<String> bookingCells;
    private final boolean rowMatched;
    private List<String> continuationLines = List.of();
    private boolean firstOfSection;
    private String balanceStated;
    private List<BalanceLine> balanceLinesAfter = List.of();
    private boolean underHeader;
    private String otherPattern;
    private boolean overflowing;

    private Builder(String line, List<String> cells, boolean matched) {
      this.bookingLine = line;
      this.bookingCells = List.copyOf(cells);
      this.rowMatched = matched;
    }

    public Builder withContinuation(List<String> lines) {
      continuationLines = List.copyOf(lines);
      return this;
    }

    public Builder withSectionStart(boolean value) {
      firstOfSection = value;
      return this;
    }

    public Builder withStatedBalance(String balance) {
      balanceStated = balance;
      return this;
    }

    public Builder withBalancesAfter(List<BalanceLine> lines) {
      balanceLinesAfter = List.copyOf(lines);
      return this;
    }

    public Builder withHeaded(boolean value) {
      underHeader = value;
      return this;
    }

    public Builder withAlsoFoundBy(String pattern) {
      otherPattern = pattern;
      return this;
    }

    public Builder withContinuationOverflow(boolean value) {
      overflowing = value;
      return this;
    }

    public ImportPdfBookingLine build() {
      return new ImportPdfBookingLine(
          bookingLine,
          bookingCells,
          rowMatched,
          continuationLines,
          firstOfSection,
          balanceStated,
          balanceLinesAfter,
          underHeader,
          otherPattern,
          overflowing);
    }
  }

  /** The line as read, with its continuation lines below it: what the row's raw data keeps. */
  public String fullText() {
    if (continuation.isEmpty()) {
      return line;
    }
    return line + "\n" + String.join("\n", continuation);
  }
}
