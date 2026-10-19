package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * US-07-13 (#273): what a holdings statement says, as its template's {@link ImportHoldingsRule}
 * reads it - before anything is resolved against the security master or the account.
 *
 * @param date the snapshot's date
 * @param dateSource {@link ImportHoldingsValues#DATE_STATEMENT} or {@link
 *     ImportHoldingsValues#DATE_MEMBER}
 * @param lines every position, cash balance and unreadable line, numbered in file order from 1
 */
public record ImportHoldingsStatement(LocalDate date, String dateSource, List<Line> lines) {

  public ImportHoldingsStatement {
    lines = List.copyOf(lines);
  }

  /** The lines of {@code kind} ({@link ImportHoldingsValues}), in file order. */
  public List<Line> linesOf(String kind) {
    return lines.stream().filter(line -> kind.equals(line.kind())).toList();
  }

  /**
   * One line as read. A position has an ISIN or a symbol (or both), a quantity, a value and their
   * currency, and possibly a name and a price; a cash balance a currency and an amount; an {@code
   * ERROR} line its text and error code only.
   *
   * @param rawText what the line said: a PDF line or a row's cells, for an unreadable line only
   * @param warnings warning codes ({@link ImportHoldingsValues#WARNING_VALUE_MISMATCH})
   */
  public record Line(
      int rowNumber,
      String kind,
      String isin,
      String symbol,
      String name,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal value,
      String currency,
      BigDecimal amount,
      String rawText,
      String errorCode,
      List<String> warnings) {

    public Line {
      warnings = List.copyOf(warnings);
    }

    public static Line position(
        int rowNumber,
        String isin,
        String symbol,
        String name,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal value,
        String currency,
        List<String> warnings) {
      return new Line(
          rowNumber,
          ImportHoldingsValues.KIND_POSITION,
          isin,
          symbol,
          name,
          quantity,
          price,
          value,
          currency,
          null,
          null,
          null,
          warnings);
    }

    public static Line cash(int rowNumber, String currency, BigDecimal amount) {
      return new Line(
          rowNumber,
          ImportHoldingsValues.KIND_CASH,
          null,
          null,
          null,
          null,
          null,
          null,
          currency,
          amount,
          null,
          null,
          List.of());
    }

    public static Line error(int rowNumber, String rawText, String errorCode) {
      return new Line(
          rowNumber,
          ImportHoldingsValues.KIND_ERROR,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          rawText,
          errorCode,
          List.of());
    }
  }
}
