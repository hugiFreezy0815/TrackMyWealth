package com.trackmywealth.backend.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A booking line of a statement while it is read (#268), with what surrounds it: the table columns
 * in force (from the last header line), the section it is in, the balance a balance line stated
 * before it, the lines below it that continue it, until a line that does not ends it, and the
 * balances that balance lines below it state before the next booking of its section.
 */
public final class PdfBooking {

  private final PdfTextLine bookingLine;
  private final PdfColumns tableColumns;
  private final String sectionValue;
  private final boolean startsSection;
  private final String balanceBefore;
  private final List<PdfTextLine> continuationLines = new ArrayList<>();
  private final List<String> balancesBelow = new ArrayList<>();
  private boolean open = true;

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
  }

  /** Appends {@code next} as a line continuing this booking, while it is open. */
  public void continueWith(PdfTextLine next) {
    if (open) {
      continuationLines.add(next);
    }
  }

  /** Records a balance that a balance line below this booking states, as written. */
  public void addBalanceAfter(String balance) {
    balancesBelow.add(balance);
  }

  /** Ends this booking: no later line continues it. */
  public void end() {
    open = false;
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

  public List<PdfTextLine> continuation() {
    return List.copyOf(continuationLines);
  }

  public List<String> balancesAfter() {
    return List.copyOf(balancesBelow);
  }
}
