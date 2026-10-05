package com.trackmywealth.backend.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One booking line of a PDF statement (#268): a line the template's record-start pattern finds.
 * When its row pattern matches too, {@code cells} are the pattern's capture groups, one per layout
 * column. When it does not, the line is still kept, never dropped: {@code matched} is false and
 * {@code cells} holds the whole line, which the parser reports as {@link
 * ImportRowErrorValues#LINE_UNMATCHED}.
 */
public record ImportPdfBookingLine(String line, List<String> cells, boolean matched) {

  public ImportPdfBookingLine {
    cells = Collections.unmodifiableList(new ArrayList<>(cells));
  }

  /** A line the row pattern does not match. */
  public static ImportPdfBookingLine unmatched(String line) {
    return new ImportPdfBookingLine(line, List.of(line), false);
  }
}
