package com.trackmywealth.backend.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One booking line of a PDF statement (#268): a line the template's record-start pattern finds.
 * When its row pattern matches too, {@code cells} are the pattern's capture groups, one per layout
 * column, then the words under each header label and the section's value, per {@link
 * ImportPdfLayout#cellNames()}. Any other line is still kept, never dropped: its {@code status}
 * says why it is no booking to import, and {@code cells} holds the whole line.
 *
 * @param status what the line was read as; the parser reports every status but {@link
 *     Status#MATCHED} as the row's error
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
    Status status,
    List<String> continuation,
    boolean sectionStart,
    String statedBalance,
    List<BalanceLine> balancesAfter,
    boolean headed) {

  /**
   * What a line the record-start pattern finds was read as (#268). Each status but {@link #MATCHED}
   * is one row error.
   */
  public enum Status {
    /** A booking line its row pattern matches. */
    MATCHED(true, null),
    /**
     * A booking line its row pattern does not match: {@link ImportRowErrorValues#LINE_UNMATCHED}.
     */
    UNMATCHED(true, null),
    /**
     * A booking line that more lines would continue than {@link
     * ImportPdfLayout#MAX_CONTINUATION_LINES}: {@link ImportRowErrorValues#CONTINUATION_TOO_LONG}.
     */
    CONTINUATION_TOO_LONG(true, null),
    /**
     * A section's start that the record-start pattern finds too, read as the section's start:
     * {@link ImportRowErrorValues#LINE_AMBIGUOUS}.
     */
    ALSO_SECTION_START(false, "sectionPattern"),
    /**
     * A balance line that the record-start pattern finds too, read as a balance line: {@link
     * ImportRowErrorValues#LINE_AMBIGUOUS}.
     */
    ALSO_BALANCE_LINE(false, "balanceLinePattern"),
    /**
     * A header line of the layout's header labels that the record-start pattern finds too, read as
     * a header line: {@link ImportRowErrorValues#LINE_AMBIGUOUS}.
     */
    ALSO_HEADER_LINE(false, "headerLabels"),
    /**
     * A line before the layout's first section, where no booking is read: {@link
     * ImportRowErrorValues#LINE_BEFORE_SECTION}.
     */
    BEFORE_FIRST_SECTION(false, null);

    private final boolean bookingLine;
    private final String patternName;

    Status(boolean bookingLine, String patternName) {
      this.bookingLine = bookingLine;
      this.patternName = patternName;
    }

    /**
     * Whether the line was read as a booking, parsed or not: only a booking line reads or moves the
     * running balance.
     */
    public boolean isBooking() {
      return bookingLine;
    }

    /** The layout field that read the line as its own, for an ambiguous line; else {@code null}. */
    public String otherPattern() {
      return patternName;
    }
  }

  /**
   * A balance line below a booking (e.g. a section's closing balance).
   *
   * @param line the line as read
   * @param balance the balance it states, as written
   */
  public record BalanceLine(String line, String balance) {}

  public ImportPdfBookingLine {
    Objects.requireNonNull(status, "status");
    cells = Collections.unmodifiableList(new ArrayList<>(cells));
    continuation = List.copyOf(continuation);
    balancesAfter = List.copyOf(balancesAfter);
  }

  /**
   * A booking line with its cells, and nothing around it until the builder's {@code with...}
   * methods add it: each optional component by its name, never by its place.
   */
  public static Builder builder(String line, List<String> cells, Status status) {
    return new Builder(line, cells, status);
  }

  /**
   * A line that is no booking to read cells from ({@code status} anything but {@link
   * Status#MATCHED}): its one cell is the whole line, and nothing surrounds it.
   */
  public static ImportPdfBookingLine unread(String line, Status status) {
    return builder(line, List.of(line), status).build();
  }

  /** Builds an {@link ImportPdfBookingLine}; each optional component defaults to "none". */
  public static final class Builder {

    private final String bookingLine;
    private final List<String> bookingCells;
    private final Status lineStatus;
    private List<String> continuationLines = List.of();
    private boolean firstOfSection;
    private String balanceStated;
    private List<BalanceLine> balanceLinesAfter = List.of();
    private boolean underHeader;

    private Builder(String line, List<String> cells, Status status) {
      this.bookingLine = line;
      this.bookingCells = List.copyOf(cells);
      this.lineStatus = status;
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

    public ImportPdfBookingLine build() {
      return new ImportPdfBookingLine(
          bookingLine,
          bookingCells,
          lineStatus,
          continuationLines,
          firstOfSection,
          balanceStated,
          balanceLinesAfter,
          underHeader);
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
