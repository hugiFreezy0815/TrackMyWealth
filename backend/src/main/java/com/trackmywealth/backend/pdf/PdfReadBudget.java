package com.trackmywealth.backend.pdf;

import java.time.Duration;

/**
 * The drawing operations, the characters and the time one read of a PDF's pages may take (#268,
 * #276). The stream limits bound how much a stream decodes to, not how often it runs: a form that
 * draws another form twenty times, nested a few levels deep, makes a two-kilobyte file run for
 * hours. A real statement page runs a few thousand operations.
 *
 * <p>Characters are counted one by one, not per operation: a single text operator can show millions
 * of them, and the text stripper keeps an object for each until its page is done, so a
 * three-kilobyte file showing one long string exhausted the heap before any check after the page
 * could run (fourth PR #267 review).
 *
 * <p>PDFBox swallows an exception thrown under a {@code Do} operator (it logs it and goes on with
 * the next one), so an exhausted budget never throws mid-page: from then on every operation and
 * character is skipped, and {@link #requireWithinLimits()}, called after each page, refuses the
 * document. One budget belongs to one read on one thread.
 */
public final class PdfReadBudget {

  // The clock is read once per this many operations.
  private static final int CLOCK_INTERVAL = 1024;

  private final long maxOperations;
  private final long maxCharacters;
  private final long deadline;
  private long operations;
  private long characters;
  private PdfLimitException refusal;

  public PdfReadBudget(long maxOperations, long maxCharacters, Duration time) {
    this.maxOperations = maxOperations;
    this.maxCharacters = maxCharacters;
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

  /** Whether one more character may be shown; once one may not, nothing runs again. */
  boolean allowCharacter() {
    if (refusal != null) {
      return false;
    }
    characters++;
    if (characters > maxCharacters) {
      refuse(new PdfLimitException("The PDF holds more text than one statement may."));
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
   * @throws PdfLimitException when an operation or a character was refused, or the time is up
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
