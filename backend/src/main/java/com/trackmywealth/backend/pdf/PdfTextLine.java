package com.trackmywealth.backend.pdf;

import java.util.Collections;
import java.util.List;

/**
 * One line of a page's text layer (#268): its text exactly as PDFBox's text stripper writes it, and
 * its words with their positions. A line read by OCR has text but no words.
 *
 * @param page the 1-based page the line is on
 */
public record PdfTextLine(int page, String text, List<PdfWord> words) {

  public PdfTextLine {
    words = Collections.unmodifiableList(List.copyOf(words));
  }
}
