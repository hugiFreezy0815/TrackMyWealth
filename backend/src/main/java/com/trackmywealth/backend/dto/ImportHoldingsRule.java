package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * US-07-13 (#273, INPUT-004): how a {@code SNAPSHOT} template reads a holdings statement - its
 * date, its positions and its cash per currency.
 *
 * <p><b>PDF.</b> Every text line is tried, stripped, against RE2 patterns:
 *
 * <ul>
 *   <li>{@code documentMarker}: text every statement of the layout contains (detection, as {@link
 *       ImportPdfLayout#documentMarker()});
 *   <li>{@code datePattern}, one named group {@value #DATE_GROUP}: its first match with a readable
 *       date is the snapshot's date, read with {@code dateFormat} or the template's date format;
 *   <li>{@code sectionPattern}, optional, one named group {@value #CURRENCY_GROUP}: a line starting
 *       a section of positions quoted in that currency ({@code Aktien in USD});
 *   <li>{@code positionPatterns}, one to {@value #MAX_POSITION_PATTERNS}: a line any of them
 *       matches is a position, with the named groups {@value #QUANTITY_GROUP} and {@value
 *       #VALUE_GROUP}, at least one of {@value #ISIN_GROUP} and {@value #SYMBOL_GROUP}, and
 *       optionally {@value #PRICE_GROUP}, {@value #NAME_GROUP} and {@value #CURRENCY_GROUP} (else
 *       the section's currency);
 *   <li>{@code cashPattern}, optional, the named groups {@value #CURRENCY_GROUP} and {@value
 *       #AMOUNT_GROUP}: a cash balance.
 * </ul>
 *
 * <p><b>Spreadsheet.</b> {@code columns} names header cells: a data row with a quantity is a
 * position, one with a cash amount and a currency code is a cash balance, any other row (a group
 * heading, a subtotal) is passed over. The date is the one the member enters at upload: a
 * spreadsheet export states none.
 *
 * <p>Amounts are read with the template's separators. A line or row a pattern took for a position
 * or a cash balance but whose values cannot be read is never dropped: it blocks the commit.
 */
public record ImportHoldingsRule(
    @Schema(description = "PDF: text every statement of this layout contains.")
        @Size(max = ImportPdfLayout.MAX_MARKER_LENGTH)
        String documentMarker,
    @Schema(
            description =
                "PDF: RE2 pattern with the one named group date, e.g. Portfolio per"
                    + " (?P<date>\\S+); its first readable match dates the snapshot.")
        @Size(max = ImportPdfLayout.MAX_PATTERN_LENGTH)
        String datePattern,
    @Schema(description = "Optional: the date's format, if not the template's date format.")
        @Size(max = ImportStatementBalanceRule.MAX_DATE_FORMAT_LENGTH)
        String dateFormat,
    @Schema(
            description =
                "PDF, optional: RE2 pattern with the one named group currency, starting a section"
                    + " of positions quoted in it, e.g. ^Aktien in (?P<currency>[A-Z]{3})$.")
        @Size(max = ImportPdfLayout.MAX_PATTERN_LENGTH)
        String sectionPattern,
    @Schema(
            description =
                "PDF: RE2 patterns of a position line, with the named groups quantity and value,"
                    + " isin and/or symbol, and optionally price, name and currency.")
        @Size(max = MAX_POSITION_PATTERNS)
        List<@Size(max = ImportPdfLayout.MAX_PATTERN_LENGTH) String> positionPatterns,
    @Schema(
            description =
                "PDF, optional: RE2 pattern of a cash balance line with the named groups currency"
                    + " and amount.")
        @Size(max = ImportPdfLayout.MAX_PATTERN_LENGTH)
        String cashPattern,
    @Schema(description = "Spreadsheet: the header cells a position or cash row is read from.")
        @Valid
        ImportHoldingsColumns columns) {

  public static final String DATE_GROUP = "date";
  public static final String CURRENCY_GROUP = "currency";
  public static final String ISIN_GROUP = "isin";
  public static final String SYMBOL_GROUP = "symbol";
  public static final String NAME_GROUP = "name";
  public static final String QUANTITY_GROUP = "quantity";
  public static final String PRICE_GROUP = "price";
  public static final String VALUE_GROUP = "value";
  public static final String AMOUNT_GROUP = "amount";
  public static final int MAX_POSITION_PATTERNS = 5;

  public ImportHoldingsRule {
    // A null pattern from the client becomes a blank one, which validation refuses with a 422.
    positionPatterns =
        positionPatterns == null
            ? List.of()
            : List.copyOf(positionPatterns.stream().map(p -> p == null ? "" : p).toList());
  }

  /**
   * US-07-13: the header cells of a spreadsheet holdings statement. {@code quantity} and {@code
   * value} are mapped together, with {@code currency} and at least one of {@code isin} and {@code
   * symbol}; {@code cashAmount} with {@code currency}. At least one of the two is mapped.
   */
  public record ImportHoldingsColumns(
      @Size(max = MAX_COLUMN_LENGTH) String isin,
      @Size(max = MAX_COLUMN_LENGTH) String symbol,
      @Size(max = MAX_COLUMN_LENGTH) String name,
      @Size(max = MAX_COLUMN_LENGTH) String quantity,
      @Size(max = MAX_COLUMN_LENGTH) String price,
      @Size(max = MAX_COLUMN_LENGTH) String value,
      @Size(max = MAX_COLUMN_LENGTH) String currency,
      @Size(max = MAX_COLUMN_LENGTH) String cashAmount) {

    public static final int MAX_COLUMN_LENGTH = 200;

    /** Whether a row can be a position: quantity and value are mapped. */
    public boolean mapsPositions() {
      return quantity != null || value != null;
    }

    /** Whether a row can be a cash balance. */
    public boolean mapsCash() {
      return cashAmount != null;
    }
  }
}
