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
 * <p>It may also be a line that is no booking for sure (PR #281 review): one that another of the
 * layout's patterns finds as well, or one before the first section, which must be reported, never
 * silently dropped or imported.
 */
public final class PdfBooking {

  /** A balance line below a booking: the line as written, and the balance it states. */
  public record BalanceLine(String text, String balance) {}

  private final PdfTextLine bookingLine;
  private final PdfColumns tableColumns;
  private final String sectionValue;
  private final boolean startsSection;
  private final String balanceBefore;
  private final List<PdfTextLine> continuationLines = new ArrayList<>();
  private final List<BalanceLine> balancesBelow = new ArrayList<>();
  private final String foundByAlso;
  private final boolean outsideSections;
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
    this.foundByAlso = null;
    this.outsideSections = false;
  }

  private PdfBooking(PdfTextLine line, String otherPattern, boolean beforeFirstSection) {
    this.bookingLine = line;
    this.tableColumns = null;
    this.sectionValue = null;
    this.startsSection = false;
    this.balanceBefore = null;
    this.foundByAlso = otherPattern;
    this.outsideSections = beforeFirstSection;
    this.open = false;
  }

  /**
   * A booking line that {@code otherPattern} (the name of another pattern of the layout) finds as
   * well, and that was read as that pattern's line: it has nothing around it and is never
   * continued.
   */
  public static PdfBooking ambiguous(PdfTextLine line, String otherPattern) {
    return new PdfBooking(line, otherPattern, false);
  }

  /**
   * A line the record-start pattern finds before the layout's first section, where no booking is
   * read: it has nothing around it and is never continued.
   */
  public static PdfBooking beforeFirstSection(PdfTextLine line) {
    return new PdfBooking(line, null, true);
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

  /** The other pattern that finds this line too, when it is no booking for sure. */
  public Optional<String> alsoFoundBy() {
    return Optional.ofNullable(foundByAlso);
  }

  /** Whether it is a line before the first section, no booking for sure. */
  public boolean beforeFirstSection() {
    return outsideSections;
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
