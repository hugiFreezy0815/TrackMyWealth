package com.trackmywealth.backend.pdf;

import java.time.Duration;

/**
 * The drawing operations and the time one read of a PDF's pages may take (#268, #276). The stream
 * limits bound how much a stream decodes to, not how often it runs: a form that draws another form
 * twenty times, nested a few levels deep, makes a two-kilobyte file run for hours. A real statement
 * page runs a few thousand operations.
 *
 * <p>PDFBox swallows an exception thrown under a {@code Do} operator (it logs it and goes on with
 * the next one), so an exhausted budget never throws mid-page: from then on every operation is
 * skipped, and {@link #requireWithinLimits()}, called after each page, refuses the document. One
 * budget belongs to one read on one thread.
 */
public final class PdfReadBudget {

  // The clock is read once per this many operations.
  private static final int CLOCK_INTERVAL = 1024;

  private final long maxOperations;
  private final long deadline;
  private long operations;
  private PdfLimitException refusal;

  public PdfReadBudget(long maxOperations, Duration time) {
    this.maxOperations = maxOperations;
    this.deadline = System.nanoTime() + time.toNanos();
  }

  /** Whether one more operation may run; once one may not, none does again. */
  boolean allowOperation() {
    if (refusal != null) {
      return false;
    }
    operations++;
    if (operations > maxOperations) {
      refuse(new PdfLimitException("The PDF draws more than a statement needs."));
    } else if (operations % CLOCK_INTERVAL == 0) {
      checkTime();
    }
    return refusal == null;
  }

  /** Refuses the document for {@code limit}; the first refusal is the one reported. */
  void refuse(PdfLimitException limit) {
    if (refusal == null) {
      refusal = limit;
    }
  }

  /**
   * @throws PdfLimitException when an operation was refused, or the time is up
   */
  public void requireWithinLimits() throws PdfLimitException {
    checkTime();
    if (refusal != null) {
      throw refusal;
    }
  }

  private void checkTime() {
    if (System.nanoTime() - deadline > 0) {
      refuse(new PdfLimitException("Reading the PDF takes longer than a statement may."));
    }
  }
}
