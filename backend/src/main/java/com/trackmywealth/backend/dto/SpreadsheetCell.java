package com.trackmywealth.backend.dto;

import java.math.BigDecimal;

/**
 * #273: one cell of a spreadsheet import file as read: its text, and for a cell that holds a number
 * that number - a spreadsheet stores it as a binary double, never in the template's separators, so
 * it is never read back from text. A date cell's text is its ISO date ({@code 2031-12-31}).
 */
public record SpreadsheetCell(String text, BigDecimal number) {

  public static final SpreadsheetCell BLANK = new SpreadsheetCell("", null);

  public SpreadsheetCell {
    text = text == null ? "" : text;
  }

  public boolean isBlank() {
    return number == null && text.isBlank();
  }
}
