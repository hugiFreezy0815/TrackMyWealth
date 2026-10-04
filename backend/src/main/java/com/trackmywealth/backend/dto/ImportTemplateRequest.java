package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request body of {@code POST} and {@code PUT /api/v1/import-templates} and the {@code template}
 * part of {@code POST /api/v1/import-templates/test} (US-07-03). A field left {@code null} takes
 * the schema default (V15); {@code PUT} replaces the whole template, so it does the same.
 *
 * <p>{@code headerColumns} are the header cells of the sample file as the dry run returned them.
 * The server derives the header fingerprint from them (FR-IMP-022) and checks every mapping by name
 * against them. On {@code PUT}, {@code null} keeps the stored ones; a template for a file without a
 * header row has none.
 */
public record ImportTemplateRequest(
    @NotBlank @Size(max = MAX_NAME_LENGTH) String name,
    UUID institutionCatalogueId,
    @Pattern(regexp = "CASH_TRANSACTIONS|SECURITIES_TRANSACTIONS|SNAPSHOT") String templateClass,
    @Size(min = 1, max = 1) String delimiter,
    @Schema(allowableValues = {"UTF-8", "ISO-8859-1", "windows-1252"}) String encoding,
    @Size(min = 1, max = 1) String decimalSeparator,
    @Size(min = 1, max = 1) String thousandsSeparator,
    @Schema(description = "java.time pattern, e.g. dd.MM.uuuu (yyyy is accepted as well).")
        @Size(max = 40)
        String dateFormat,
    @Min(ImportTemplateValues.NO_HEADER_ROW) @Max(MAX_SKIPPED_ROWS) Integer headerRowIndex,
    @Min(0) @Max(MAX_SKIPPED_ROWS) Integer preambleRowCount,
    @Min(0) @Max(MAX_SKIPPED_ROWS) Integer trailingSummaryRowCount,
    @Pattern(regexp = "SINGLE_SIGNED_COLUMN|SEPARATE_DEBIT_CREDIT|NEGATIVE_IN_PARENTHESES")
        String amountRepresentation,
    @Pattern(regexp = "FIXED|PER_ROW|FROM_ACCOUNT") String currencyMode,
    @Size(min = 3, max = 3) String fixedCurrency,
    @NotNull ImportColumnMapping columnMapping,
    @Size(max = MAX_TYPE_MAPPINGS) Map<String, String> typeMapping,
    @Pattern(regexp = "USER_SELECTED|COLUMN|PREAMBLE_LINE|FILENAME")
        String accountIdentificationStrategy,
    @Size(max = MAX_HEADER_COLUMNS) List<String> headerColumns) {

  public ImportTemplateRequest {
    // Copies that keep null entries: a client's null must reach validation as a 422, not an NPE.
    // A null headerColumns is kept as it is: on PUT it means "keep the stored ones".
    typeMapping =
        Collections.unmodifiableMap(
            typeMapping == null ? new LinkedHashMap<>() : new LinkedHashMap<>(typeMapping));
    if (headerColumns != null) {
      headerColumns = Collections.unmodifiableList(new ArrayList<>(headerColumns));
    }
  }

  public static final int MAX_NAME_LENGTH = 200;
  // SMALLINT columns; no real export has more than a handful of preamble or summary lines.
  public static final int MAX_SKIPPED_ROWS = 100;
  public static final int MAX_TYPE_MAPPINGS = 500;
  public static final int MAX_HEADER_COLUMNS = 500;

  /** The parse-relevant part, with the schema defaults (V15) for every field left out. */
  public ImportTemplateDefinition toDefinition() {
    return new ImportTemplateDefinition(
        orDefault(templateClass, ImportTemplateValues.CLASS_CASH_TRANSACTIONS),
        orDefault(delimiter, ","),
        orDefault(encoding, ImportTemplateValues.ENCODING_UTF_8),
        orDefault(decimalSeparator, "."),
        thousandsSeparator,
        orDefault(dateFormat, "yyyy-MM-dd"),
        headerRowIndex == null ? 0 : headerRowIndex,
        preambleRowCount == null ? 0 : preambleRowCount,
        trailingSummaryRowCount == null ? 0 : trailingSummaryRowCount,
        orDefault(amountRepresentation, ImportTemplateValues.AMOUNT_SINGLE_SIGNED_COLUMN),
        orDefault(currencyMode, ImportTemplateValues.CURRENCY_FIXED),
        fixedCurrency,
        columnMapping,
        typeMapping,
        orDefault(accountIdentificationStrategy, ImportTemplateValues.ACCOUNT_USER_SELECTED));
  }

  private static String orDefault(String value, String defaultValue) {
    return value == null ? defaultValue : value;
  }
}
