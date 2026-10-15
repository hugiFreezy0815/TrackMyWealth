package com.trackmywealth.backend.dto;

import java.util.List;

/**
 * A PDF statement as one read gives it (#268, US-07-12): its booking lines, and every text line in
 * reading order, which a statement balance rule is searched in.
 */
public record ImportPdfStatement(List<ImportPdfBookingLine> bookingLines, List<String> lines) {

  public ImportPdfStatement {
    bookingLines = List.copyOf(bookingLines);
    lines = List.copyOf(lines);
  }
}
