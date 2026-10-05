package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 */
@Schema(
    description =
        "Line-based extraction of a PDF statement: one booking per line that recordStartPattern"
            + " finds, its cells the capture groups of rowPattern, named by columns.")
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
        String recordStartPattern) {

  public static final int MAX_COLUMNS = 50;
  public static final int MAX_PATTERN_LENGTH = 1000;
  public static final int MAX_MARKER_LENGTH = 200;
  // A column name is echoed in every row's raw data and error, like a CSV file's header cell.
  public static final int MAX_COLUMN_NAME_LENGTH = 100;

  public ImportPdfLayout {
    // A copy that keeps null entries: a client's null column must reach validation as a 422.
    columns = Collections.unmodifiableList(new ArrayList<>(columns == null ? List.of() : columns));
  }
}
