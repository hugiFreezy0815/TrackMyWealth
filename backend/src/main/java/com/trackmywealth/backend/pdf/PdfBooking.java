package com.trackmywealth.backend.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A booking line of a statement while it is read (#268), with what surrounds it: the table columns
 * in force (from the last header line), the section it is in, the balance a balance line stated
 * before it, the lines below it that continue it, until a line that does not ends it, and the
 * balances that balance lines below it state before the next booking of its section.
 *
 * <p>It may also be a line that is no booking for sure (its {@link Kind}): one that another of the
 * layout's patterns finds as well, or one before the first section, which must be reported, never
 * silently dropped or imported.
 */
public final class PdfBooking {

  /** What a line the record-start pattern finds was read as. */
  public enum Kind {
    /** A booking line. */
    BOOKING,
    /** A section's start that the record-start pattern finds too. */
    ALSO_SECTION_START,
    /** A balance line that the record-start pattern finds too. */
    ALSO_BALANCE_LINE,
    /** A header line of the layout's labels that the record-start pattern finds too. */
    ALSO_HEADER_LINE,
    /** A line before the first section, where no booking is read. */
    BEFORE_FIRST_SECTION
  }

  /** A balance line below a booking: the line as written, and the balance it states. */
  public record BalanceLine(String text, String balance) {}

  private final PdfTextLine bookingLine;
  private final PdfColumns tableColumns;
  private final String sectionValue;
  private final boolean startsSection;
  private final String balanceBefore;
  private final List<PdfTextLine> continuationLines = new ArrayList<>();
  private final List<BalanceLine> balancesBelow = new ArrayList<>();
  private final Kind lineKind;
  private boolean open = true;
  // Paused by a balance line below it: a carry-forward line at the top of a later page may still
  // carry it over to there (PR #281 review).
  private boolean paused;
  // The page a carry-forward line carried it over to; 0 for none.
  private int carriedTo;
  private boolean overflowed;

  /**
   * @param columns the columns of the last header line; {@code null} before one
   * @param section the value of the section's marker; {@code null} outside sections
   * @param sectionStart whether it is the section's first booking
   * @param statedBalance the balance stated since the booking before it; {@code null} for none
   */
  public PdfBooking(
      PdfTextLine line,
      PdfColumns columns,
      String section,
      boolean sectionStart,
      String statedBalance) {
    this.bookingLine = line;
    this.tableColumns = columns;
    this.sectionValue = section;
    this.startsSection = sectionStart;
    this.balanceBefore = statedBalance;
    this.lineKind = Kind.BOOKING;
  }

  private PdfBooking(PdfTextLine line, Kind kind) {
    this.bookingLine = line;
    this.tableColumns = null;
    this.sectionValue = null;
    this.startsSection = false;
    this.balanceBefore = null;
    this.lineKind = kind;
    this.open = false;
  }

  /**
   * A line that is no booking for sure, read as {@code kind} (anything but {@link Kind#BOOKING}):
   * it has nothing around it and is never continued.
   */
  public static PdfBooking noBooking(PdfTextLine line, Kind kind) {
    if (kind == Kind.BOOKING) {
      throw new IllegalArgumentException("A booking line has its columns, section and balance.");
    }
    return new PdfBooking(line, kind);
  }

  /** Appends {@code next} as a line continuing this booking, while it is open. */
  public void continueWith(PdfTextLine next) {
    if (open) {
      continuationLines.add(next);
    }
  }

  /**
   * Ends this booking because more lines would continue it than a booking may have: what follows is
   * likely no part of it (e.g. a statement's closing text), so it is reported.
   */
  public void endOverflowing() {
    overflowed = true;
    end();
  }

  /** Records a balance line below this booking, and the balance it states as written. */
  public void addBalanceAfter(PdfTextLine line, String balance) {
    balancesBelow.add(new BalanceLine(line.text().strip(), balance));
  }

  /** Ends this booking: no later line continues it. */
  public void end() {
    open = false;
    paused = false;
  }

  /**
   * A balance line below this booking: on a later page than the booking's last line (a
   * carry-forward line at that page's top), it carries the booking over to that page, whose lines
   * may continue it; otherwise it pauses the booking, which only such a line can then carry over.
   * Statements print the balance at the foot of a page and again at the top of the next, between a
   * booking split across the page and its continuation lines (PR #281 review).
   */
  public void balanceLine(PdfTextLine line) {
    if (open || paused) {
      open = false;
      paused = true;
      carriedTo = line.page() > lastPage() ? line.page() : 0;
    }
  }

  /**
   * Opens this booking again for {@code next} when a carry-forward line carried it over to {@code
   * next}'s page.
   */
  public void resumeFor(PdfTextLine next) {
    if (paused && carriedTo == next.page()) {
      open = true;
      paused = false;
    }
  }

  // The page of its last line, continuation lines included.
  private int lastPage() {
    return continuationLines.isEmpty()
        ? bookingLine.page()
        : continuationLines.get(continuationLines.size() - 1).page();
  }

  public boolean isOpen() {
    return open;
  }

  public PdfTextLine line() {
    return bookingLine;
  }

  public Optional<PdfColumns> columns() {
    return Optional.ofNullable(tableColumns);
  }

  public Optional<String> section() {
    return Optional.ofNullable(sectionValue);
  }

  public boolean sectionStart() {
    return startsSection;
  }

  public Optional<String> statedBalance() {
    return Optional.ofNullable(balanceBefore);
  }

  /** Whether more lines would have continued it than a booking may have. */
  public boolean overflowing() {
    return overflowed;
  }

  public Kind kind() {
    return lineKind;
  }

  public int continuationCount() {
    return continuationLines.size();
  }

  public List<PdfTextLine> continuation() {
    return List.copyOf(continuationLines);
  }

  public List<BalanceLine> balancesAfter() {
    return List.copyOf(balancesBelow);
  }
}
