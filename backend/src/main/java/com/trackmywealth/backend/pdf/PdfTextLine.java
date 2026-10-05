package com.trackmywealth.backend.pdf;

import java.util.List;

/**
 * One line of a page's text layer (#268): its text exactly as PDFBox's text stripper writes it, its
 * words with their positions, and how far it sits from the page's top and bottom edges. A line read
 * by OCR has text but no words and no place on the page.
 *
 * @param page the 1-based page the line is on
 * @param fromTop how far below the page's top edge the line's first word sits, in the page's text
 *     space; {@link Float#NaN} when unknown (no words, or OCR)
 * @param fromBottom how far above the page's bottom edge it sits; {@link Float#NaN} when unknown
 */
public record PdfTextLine(
    int page, String text, List<PdfWord> words, float fromTop, float fromBottom) {

  public PdfTextLine {
    words = List.copyOf(words);
  }

  /** A line without a known place on the page. */
  public PdfTextLine(int page, String text, List<PdfWord> words) {
    this(page, text, words, Float.NaN, Float.NaN);
  }
}
