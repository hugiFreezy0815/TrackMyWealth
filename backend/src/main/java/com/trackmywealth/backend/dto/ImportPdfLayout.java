package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * How a PDF import template (file format {@code PDF_TEXT} or {@code PDF_OCR}) reads a statement's
 * text, line by line (#268, US-07-08). The text of every page is taken in reading order (or from
 * local OCR); a line on which {@code recordStartPattern} finds a match is one booking, and the
 * capture groups of {@code rowPattern}, matched against the whole line, are its cells, named by
 * {@code columns}. The column mapping then names those columns exactly as it names a CSV file's
 * header cells. A booking line that {@code rowPattern} does not match becomes an error row, never a
 * silently dropped one.
 *
 * <p>Both patterns use RE2 syntax and are matched in linear time. {@code documentMarker} is literal
 * text that every statement of this layout contains (e.g. a title); a file without it is not this
 * template's. It identifies the document type, never an account.
 *
 * <p>The other fields are optional, and each adds to those four without changing them (#268):
 *
 * <ul>
 *   <li>{@code headerLabels}: the labels of the booking table's header line. Each word of a booking
 *       line is the cell of the label it sits under (by position on the page, so text layer only),
 *       named by the label - how an unsigned amount tells a debit from a credit. A header line is
 *       never a booking, and each one sets the columns again for the lines below it; one that
 *       {@code recordStartPattern} finds too is an error row, never a silently dropped booking. The
 *       labels are also the template's header fingerprint for detection.
 *   <li>{@code continuationColumn}: lines after a booking line that are no booking are appended to
 *       this column's cell (a counterparty, a reference). Under {@code headerLabels}, only lines
 *       that start in that column, so a remark at the margin ends the booking. Text layer only: OCR
 *       knows no page, so it cannot tell a page footer from a continuation line. A booking with
 *       more than {@value #MAX_CONTINUATION_LINES} continuation lines is an error row, never one
 *       with the rest of the statement glued to its description.
 *   <li>{@code continuationEndPattern}: a line on which it finds a match ends the booking above it,
 *       and no line after it continues one until the next booking line - e.g. the closing text
 *       after a statement's last booking.
 *   <li>{@code sectionPattern}: a line on which it finds a match starts a section; text before the
 *       first one (a summary page) is ignored, except a table header line; a line below such a
 *       header that {@code recordStartPattern} finds is an error row, never a silently dropped
 *       booking. Its first capture group is the cell {@code sectionColumn} of every booking in the
 *       section (e.g. the section's currency). A marker of the same value at the top of a later
 *       page, before any booking on it, repeats the section's title there and does not start it
 *       again. Nothing in the text tells that from a second section of the same value (a second
 *       account in that currency) opening at the page's top: such a section starts from the balance
 *       line below its marker, and without one its first booking is checked against the section
 *       before and is a balance mismatch - an error row, never a booking imported unchecked.
 *   <li>{@code balanceLinePattern}: a line on which it finds a match states a balance and is never
 *       a booking; its first capture group, when it has one, is that balance. With {@code
 *       balanceColumn}, the column holding each booking's running balance, every booking is checked
 *       to lead from the balance before it to its own: a misread amount or sign is an error row. A
 *       balance the template's amount rule does not read (e.g. {@code 1.234,56-}) checks nothing,
 *       and the booking beside it keeps its own status. Carry-forward lines that state the balance
 *       at a page break (e.g. "Uebertrag") belong in this pattern: their amount changes from page
 *       to page, so they are no page furniture, and would otherwise continue the page's last
 *       booking. One at the top of a later page carries the booking above it over, so a booking
 *       split across the page keeps its continuation lines from there.
 * </ul>
 *
 * <p>Lines repeated at the same place at the top or bottom of every page (a page header or footer,
 * page numbers aside) are dropped unless they start a section, state a balance or are a header
 * line.
 */
@Schema(
    description =
        "Line-based extraction of a PDF statement: one booking per line that recordStartPattern"
            + " finds, its cells the capture groups of rowPattern, named by columns, and, with"
            + " headerLabels, the words under each header label.")
public record ImportPdfLayout(
    @Schema(
            description =
                "Names of rowPattern's capture groups, in order; each at most "
                    + MAX_COLUMN_NAME_LENGTH
                    + " characters.")
        @Size(max = MAX_COLUMNS)
        List<String> columns,
    @Schema(description = "RE2 pattern matched against a whole booking line; one group per column.")
        @Size(max = MAX_PATTERN_LENGTH)
        String rowPattern,
    @Schema(description = "Literal text every statement of this layout contains.")
        @Size(max = MAX_MARKER_LENGTH)
        String documentMarker,
    @Schema(description = "RE2 pattern; a line on which it finds a match is a booking line.")
        @Size(max = MAX_PATTERN_LENGTH)
        String recordStartPattern,
    @Schema(
            description =
                "Optional (text layer only): the labels of the booking table's header line, in"
                    + " order. Each word of a booking line becomes the cell of the label above it.")
        @Size(max = MAX_COLUMNS)
        List<String> headerLabels,
    @Schema(
            description =
                "Optional: the column that lines following a booking line, and no booking"
                    + " themselves, are appended to.")
        @Size(max = MAX_COLUMN_NAME_LENGTH)
        String continuationColumn,
    @Schema(
            description =
                "Optional RE2 pattern; a line on which it finds a match starts a section, whose"
                    + " first capture group is the sectionColumn cell of its bookings.")
        @Size(max = MAX_PATTERN_LENGTH)
        String sectionPattern,
    @Schema(description = "Optional: the column holding the section pattern's first group.")
        @Size(max = MAX_COLUMN_NAME_LENGTH)
        String sectionColumn,
    @Schema(
            description =
                "Optional RE2 pattern; a line on which it finds a match states a balance (its first"
                    + " capture group) and is never a booking. Include carry-forward lines at page"
                    + " breaks, or they continue the page's last booking.")
        @Size(max = MAX_PATTERN_LENGTH)
        String balanceLinePattern,
    @Schema(
            description =
                "Optional: the column holding each booking's running balance; every booking is"
                    + " then checked against the balance before it.")
        @Size(max = MAX_COLUMN_NAME_LENGTH)
        String balanceColumn,
    @Schema(
            description =
                "Optional RE2 pattern; a line on which it finds a match ends the booking above it,"
                    + " and no line continues a booking until the next booking line.")
        @Size(max = MAX_PATTERN_LENGTH)
        String continuationEndPattern) {

  public static final int MAX_COLUMNS = 50;
  public static final int MAX_PATTERN_LENGTH = 1000;
  public static final int MAX_MARKER_LENGTH = 200;
  // A column name is echoed in every row's raw data and error, like a CSV file's header cell.
  public static final int MAX_COLUMN_NAME_LENGTH = 100;
  // A counterparty, an IBAN, a reference and a few remark lines; more is the statement's own text.
  public static final int MAX_CONTINUATION_LINES = 20;

  public ImportPdfLayout {
    // Copies that keep null entries: a client's null column must reach validation as a 422. No
    // header labels, as in every layout saved before them, is an empty list.
    columns = Collections.unmodifiableList(new ArrayList<>(columns == null ? List.of() : columns));
    headerLabels =
        Collections.unmodifiableList(
            new ArrayList<>(headerLabels == null ? List.of() : headerLabels));
  }

  /** A layout of the four fields every PDF layout has, and none of the optional ones. */
  public ImportPdfLayout(
      List<String> columns, String rowPattern, String documentMarker, String recordStartPattern) {
    this(
        columns,
        rowPattern,
        documentMarker,
        recordStartPattern,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * A layout of the four fields every PDF layout has, to which the builder's {@code with...}
   * methods add the optional ones by name, never by their place among eleven.
   */
  public static Builder builder(
      List<String> columns, String rowPattern, String documentMarker, String recordStartPattern) {
    return new Builder(columns, rowPattern, documentMarker, recordStartPattern);
  }

  /** A builder holding this layout's fields, to change some of them. */
  public Builder toBuilder() {
    return builder(columns, rowPattern, documentMarker, recordStartPattern)
        .withHeaderLabels(headerLabels)
        .withContinuationColumn(continuationColumn)
        .withSectionPattern(sectionPattern)
        .withSectionColumn(sectionColumn)
        .withBalanceLinePattern(balanceLinePattern)
        .withBalanceColumn(balanceColumn)
        .withContinuationEndPattern(continuationEndPattern);
  }

  /**
   * Every cell a booking line has, in order: the row pattern's columns, then the header labels,
   * then the section column. The column mapping names them as it names a CSV file's header cells.
   */
  public List<String> cellNames() {
    List<String> names = new ArrayList<>(columns);
    names.addAll(headerLabels);
    if (sectionColumn != null) {
      names.add(sectionColumn);
    }
    return names;
  }

  /** Whether the cells under the header labels are read (positions on the page needed). */
  public boolean hasHeaderLabels() {
    return !headerLabels.isEmpty();
  }

  /** Builds an {@link ImportPdfLayout}; each optional field defaults to none. */
  public static final class Builder {

    // Copies that keep null entries, as the layout's own: validation reports them as a 422.
    private final List<String> columnNames;
    private final String row;
    private final String marker;
    private final String recordStart;
    private List<String> labels = new ArrayList<>();
    private String continuation;
    private String section;
    private String sectionCell;
    private String balanceLine;
    private String balanceCell;
    private String continuationEnd;

    private Builder(
        List<String> columns, String rowPattern, String documentMarker, String recordStartPattern) {
      this.columnNames = new ArrayList<>(Objects.requireNonNullElse(columns, List.of()));
      this.row = rowPattern;
      this.marker = documentMarker;
      this.recordStart = recordStartPattern;
    }

    public Builder withHeaderLabels(List<String> value) {
      labels = new ArrayList<>(Objects.requireNonNullElse(value, List.of()));
      return this;
    }

    public Builder withContinuationColumn(String value) {
      continuation = value;
      return this;
    }

    public Builder withSectionPattern(String value) {
      section = value;
      return this;
    }

    public Builder withSectionColumn(String value) {
      sectionCell = value;
      return this;
    }

    public Builder withBalanceLinePattern(String value) {
      balanceLine = value;
      return this;
    }

    public Builder withBalanceColumn(String value) {
      balanceCell = value;
      return this;
    }

    public Builder withContinuationEndPattern(String value) {
      continuationEnd = value;
      return this;
    }

    public ImportPdfLayout build() {
      return new ImportPdfLayout(
          columnNames,
          row,
          marker,
          recordStart,
          labels,
          continuation,
          section,
          sectionCell,
          balanceLine,
          balanceCell,
          continuationEnd);
    }
  }
}
