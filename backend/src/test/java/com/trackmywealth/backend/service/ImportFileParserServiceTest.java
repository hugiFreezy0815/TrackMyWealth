package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportParseResult;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ImportRowFilter;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * US-07-03: the CSV parser engine without Spring or a database - every separator, encoding, amount
 * representation, date pattern and error code, plus the two synthetic golden fixtures (invented
 * values in the structure of a Swiss and a German bank export).
 */
class ImportFileParserServiceTest {

  private final LocalOcrService ocr = mock(LocalOcrService.class);
  private final ImportFileParserService parser =
      new ImportFileParserService(new PdfImportReaderService(ocr), new PdfBalanceCheckService());

  // --- row filter (US-07-09) ----------------------------------------------------------------

  /** Acceptance criterion 1: only accepted values are parsed; the others are SKIPPED, unread. */
  @Test
  void rowFilterSkipsPendingAndTrimsCaseInsensitiveAcceptedValues() {
    ImportTemplateDefinition template =
        new Template().rowFilter("Status", List.of("  Gebucht  ")).build();
    String csv =
        "date,amount,text,Status\n"
            + "2031-01-02,-5.00,Booked, GEBUCHT \n"
            + "not-a-date,not-an-amount,Pending,Vorgemerkt\n"
            + "2031-01-04,-8.00,Booked,gebucht\n"
            + "2031-01-05,-9.00,Unknown,\n";

    ImportParseResult result = parser.parse(utf8(csv), template, null);

    assertThat(result.rows())
        .extracting(ParsedImportRow::status)
        .containsExactly("PARSED", "SKIPPED", "PARSED", "SKIPPED");
    assertThat(result.rows()).extracting(ParsedImportRow::rowNumber).containsExactly(1, 2, 3, 4);
    ParsedImportRow pending = result.rows().get(1);
    assertThat(pending.errorCode()).isEqualTo(ImportRowErrorValues.ROW_FILTERED);
    assertThat(pending.canonical()).isNull();
    assertThat(pending.rawData()).containsEntry("Status", "Vorgemerkt");
    assertThat(result.rows().get(3).errorCode()).isEqualTo(ImportRowErrorValues.ROW_FILTERED);
  }

  /** Bank exports pad cells with non-breaking spaces; they are no part of the value either. */
  @Test
  void aFilterValuePaddedWithNonBreakingSpacesIsAccepted() {
    ImportTemplateDefinition template =
        new Template().rowFilter("Status", List.of("\u00A0Gebucht")).build();
    String csv =
        "date,amount,text,Status\n"
            + "2031-01-02,-5.00,Booked,Gebucht\u00A0\n"
            + "2031-01-03,-6.00,Booked,\u202FGEBUCHT\n"
            + "2031-01-04,-7.00,Pending,\u00A0\n";

    ImportParseResult result = parser.parse(utf8(csv), template, null);

    assertThat(result.rows())
        .extracting(ParsedImportRow::status)
        .containsExactly("PARSED", "PARSED", "SKIPPED");
  }

  /** One answer names every column the file lacks: the mapped ones and the filter's. */
  @Test
  void aMismatchNamesTheMissingFilterColumnWithTheMissingMappedOnes() {
    ImportTemplateDefinition template =
        new Template().rowFilter("Status", List.of("Gebucht")).build();

    ApiException error =
        assertFileError(
            utf8("date,amount\n2031-01-02,-5.00\n"),
            template,
            ApiErrorCode.IMPORT_TEMPLATE_MISMATCH);

    assertThat(error.getBody().getProperties())
        .containsEntry("missingColumns", List.of("text", "Status"));
  }

  /** Acceptance criterion 2: a file without the filter column names it as missing. */
  @Test
  void aHeaderLackingTheRowFilterColumnIsATemplateMismatch() {
    ImportTemplateDefinition template =
        new Template().rowFilter("Status", List.of("Gebucht")).build();

    ApiException error =
        assertFileError(
            utf8("date,amount,text\n2031-01-02,-5.00,Booked\n"),
            template,
            ApiErrorCode.IMPORT_TEMPLATE_MISMATCH);

    assertThat(error.getBody().getProperties()).containsEntry("missingColumns", List.of("Status"));
  }

  /** Template detection does not offer a template whose filter column the file lacks. */
  @Test
  void missingColumnsIncludesTheRowFilterColumn() {
    ImportTemplateDefinition template =
        new Template().rowFilter("Status", List.of("Gebucht")).build();

    assertThat(parser.missingColumns(template, List.of("date", "amount", "text", "status")))
        .isEmpty();
    assertThat(parser.missingColumns(template, List.of("date", "amount", "text")))
        .containsExactly("Status");
    assertThat(parser.missingColumns(template, List.of("date", "amount", "Status", "Status")))
        .containsExactly("text", "Status");
  }

  /**
   * A headerless file filters by index. A row too short to hold the filter column is that row's
   * error, as for a mapped column, never a silent skip.
   */
  @Test
  void aHeaderlessFileFiltersByIndexAndAShortRowIsAnError() {
    ImportTemplateDefinition template =
        new Template()
            .headerRowIndex(-1)
            .mapping(mapping("0", "1").description("2").build())
            .rowFilter("3", List.of("Gebucht"))
            .build();
    String csv =
        "2031-01-02,-5.00,Booked,Gebucht\n"
            + "2031-01-03,-7.00,Pending,Vorgemerkt\n"
            + "2031-01-04,-8.00,Short\n";

    List<ParsedImportRow> rows = parser.parse(utf8(csv), template, null).rows();

    assertThat(rows)
        .extracting(ParsedImportRow::status)
        .containsExactly("PARSED", "SKIPPED", "ERROR");
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.ROW_FILTERED);
    assertThat(rows.get(2).errorCode()).isEqualTo(ImportRowErrorValues.COLUMN_MISSING);
    assertThat(rows.get(2).errorArgs()).containsEntry("column", "3");
  }

  @ParameterizedTest
  @MethodSource("invalidRowFilters")
  void invalidRowFilterSettingsAreRejectedBeforeReading(
      ImportRowFilter filter, int headerRowIndex, String field) {
    ImportTemplateDefinition template =
        new Template()
            .headerRowIndex(headerRowIndex)
            .mapping(
                headerRowIndex < 0
                    ? mapping("0", "1").description("2").build()
                    : mapping("date", "amount").description("text").build())
            .rowFilter(filter.column(), filter.acceptedValues())
            .build();

    assertTemplateInvalid(template, field);
  }

  static Stream<Arguments> invalidRowFilters() {
    List<String> tooMany = IntStream.rangeClosed(0, 32).mapToObj(i -> "v" + i).toList();
    return Stream.of(
        Arguments.of(new ImportRowFilter(" ", List.of("Gebucht")), 0, "rowFilter.column"),
        Arguments.of(new ImportRowFilter(null, List.of("Gebucht")), 0, "rowFilter.column"),
        Arguments.of(
            new ImportRowFilter("x".repeat(201), List.of("Gebucht")), 0, "rowFilter.column"),
        Arguments.of(new ImportRowFilter("Status", List.of("Gebucht")), -1, "rowFilter.column"),
        Arguments.of(new ImportRowFilter("Status", null), 0, "rowFilter.acceptedValues"),
        Arguments.of(new ImportRowFilter("Status", List.of()), 0, "rowFilter.acceptedValues"),
        Arguments.of(
            new ImportRowFilter("Status", List.of(" ", "Gebucht")), 0, "rowFilter.acceptedValues"),
        Arguments.of(
            new ImportRowFilter("Status", Arrays.asList("Gebucht", null)),
            0,
            "rowFilter.acceptedValues"),
        Arguments.of(
            new ImportRowFilter("Status", List.of("x".repeat(101))), 0, "rowFilter.acceptedValues"),
        Arguments.of(new ImportRowFilter("Status", tooMany), 0, "rowFilter.acceptedValues"),
        Arguments.of(
            new ImportRowFilter("Status", List.of("Gebucht", " GEBUCHT ")),
            0,
            "rowFilter.acceptedValues"),
        // Only non-breaking spaces: blank as compared, so it would accept the blank cells.
        Arguments.of(
            new ImportRowFilter("Status", List.of("\u00A0\u202F", "Gebucht")),
            0,
            "rowFilter.acceptedValues"),
        Arguments.of(
            new ImportRowFilter("Status", List.of("Gebucht", "Gebucht\u00A0")),
            0,
            "rowFilter.acceptedValues"));
  }

  @Test
  void theLimitsOfARowFilterAreAccepted() {
    List<String> most = IntStream.range(0, 32).mapToObj(i -> "v" + i + "x".repeat(97)).toList();
    ImportTemplateDefinition template = new Template().rowFilter("x".repeat(200), most).build();

    parser.validateTemplate(template, null);
  }

  /** A sample header the filter column is not in, or not once, rejects the template. */
  @Test
  void theRowFilterColumnIsCheckedAgainstTheSampleHeader() {
    ImportTemplateDefinition template =
        new Template().rowFilter("Status", List.of("Gebucht")).build();

    parser.validateTemplate(template, List.of("date", "amount", "text", "STATUS"));
    for (List<String> header :
        List.of(
            List.of("date", "amount", "text"),
            List.of("date", "amount", "text", "Status", "status"))) {
      ApiException error =
          catchThrowableOfType(ApiException.class, () -> parser.validateTemplate(template, header));
      assertThat(error).as("header %s", header).isNotNull();
      assertThat(error.getBody().getProperties()).containsEntry("field", "rowFilter.column");
    }
  }

  // --- golden fixtures --------------------------------------------------------------------

  @Test
  void swissFixtureParsesEveryRowToItsExactCanonicalValues() throws IOException {
    ImportTemplateDefinition template =
        new Template()
            .encoding("ISO-8859-1")
            .delimiter(";")
            .thousands("'")
            .dateFormat("dd.MM.yyyy")
            .currencyMode("PER_ROW")
            .fixedCurrency(null)
            .mapping(
                mapping("Buchungsdatum", "Betrag")
                    .valueDate("Valuta")
                    .description("Avisierungstext")
                    .currency("W\u00e4hrung")
                    .build())
            .build();

    ImportParseResult result = parser.parse(fixture("swiss-cash-iso-8859-1.csv"), template, null);

    assertThat(result.headerColumns())
        .containsExactly(
            "Buchungsdatum", "Valuta", "Avisierungstext", "Betrag", "W\u00e4hrung", "Saldo");
    assertThat(result.rows()).allMatch(ParsedImportRow::isParsed).hasSize(5);
    assertThat(canonical(result))
        .containsExactly(
            row(
                "2026-01-02",
                "2026-01-02",
                "5250.00",
                "CHF",
                "INCOME",
                "Gutschrift Lohn Beispiel AG"),
            row(
                "2026-01-05",
                "2026-01-05",
                "-12.40",
                "CHF",
                "EXPENSE",
                "Einkauf B\u00e4ckerei Musterhausen"),
            row(
                "2026-01-10",
                "2026-01-09",
                "-1850.00",
                "CHF",
                "EXPENSE",
                "Miete Januar; Wohnung 3.OG"),
            row("2026-01-15", "2026-01-15", "-200", "CHF", "EXPENSE", "Bargeldbezug Automat"),
            row("2026-01-31", "2026-01-31", "0.15", "CHF", "INCOME", "Zins"));
  }

  @Test
  void germanFixtureSkipsPreambleAndSummaryAndReadsSollHaben() throws IOException {
    ImportTemplateDefinition template = germanTemplate();

    ImportParseResult result = parser.parse(fixture("german-cash-utf8-bom.csv"), template, null);

    assertThat(result.headerColumns().get(0)).as("the BOM is stripped").isEqualTo("Buchungstag");
    assertThat(result.rows()).allMatch(ParsedImportRow::isParsed).hasSize(5);
    List<CanonicalImportRow> rows = canonical(result);
    assertThat(rows)
        .extracting(CanonicalImportRow::amount)
        .containsExactly(
            new BigDecimal("-85.00"),
            new BigDecimal("3150.00"),
            new BigDecimal("-1204.56"),
            new BigDecimal("-4.90"),
            new BigDecimal("0.12"));
    assertThat(rows)
        .extracting(CanonicalImportRow::transactionType)
        .containsExactly("EXPENSE", "INCOME", "EXPENSE", "FEE", "INTEREST");
    assertThat(rows.get(0).counterpartyName()).isEqualTo("Stadtwerke Musterstadt");
    assertThat(rows.get(0).description()).isEqualTo("Abschlag Strom Januar");
    assertThat(rows.get(2).valueDate()).isEqualTo(LocalDate.of(2026, 1, 16));
    assertThat(rows.get(3).counterpartyName()).as("an empty cell is null").isNull();
    assertThat(rows).extracting(CanonicalImportRow::currency).containsOnly("EUR");
    assertThat(result.rows().get(4).rowNumber()).isEqualTo(5);
  }

  @Test
  void zeroAmountsAreSkippedWithTheirOriginalCellsAndCanonicalValues() {
    String csv =
        "date,amount,text\n"
            + "2031-05-02,0.00,Waived fee\n"
            + "2031-05-03,-0.0000,Information\n"
            + "2031-05-04,-5.00,Coffee\n";

    ImportParseResult result = parser.parse(utf8(csv), new Template().build(), null);

    assertThat(result.rows()).hasSize(3);
    for (int i = 0; i < 2; i++) {
      ParsedImportRow row = result.rows().get(i);
      assertThat(row.status()).isEqualTo(ImportRowErrorValues.STATUS_SKIPPED);
      assertThat(row.errorCode()).isEqualTo(ImportRowErrorValues.ZERO_AMOUNT);
      assertThat(row.errorArgs()).isEmpty();
      assertThat(row.canonical().amount()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(row.rawData()).containsKey("amount");
      assertThat(row.isParsed()).isFalse();
      assertThat(row.isSkipped()).isTrue();
      assertThat(row.isError()).isFalse();
    }
    assertThat(result.rows().get(2).isParsed()).isTrue();
    assertThat(result.rows().get(2).canonical().amount()).isEqualByComparingTo("-5.00");
  }

  @Test
  void inconsistentOriginalCurrencyFieldsAreRowErrors() {
    ImportTemplateDefinition template =
        new Template()
            .mapping(
                new MappingBuilder()
                    .bookingDate("date")
                    .amount("amount")
                    .description("text")
                    .originalAmount("original")
                    .originalCurrency("originalCurrency")
                    .build())
            .build();
    List<ParsedImportRow> rows =
        parser
            .parse(
                utf8(
                    "date,amount,text,original,originalCurrency\n"
                        + "2031-05-01,-10.00,Missing currency,-8.00,\n"
                        + "2031-05-02,-10.00,Missing amount,,EUR\n"
                        + "2031-05-03,-10.00,Zero original,0.00,EUR\n"
                        + "2031-05-04,-10.00,Wrong sign,8.00,EUR\n"
                        + "2031-05-05,-10.00,Valid original,-8.00,EUR\n"),
                template,
                "CHF",
                true)
            .rows();
    assertThat(rows.subList(0, 4)).allMatch(ParsedImportRow::isError);
    assertThat(rows.subList(0, 4))
        .extracting(ParsedImportRow::errorCode)
        .containsOnly(ImportRowErrorValues.ORIGINAL_AMOUNT_INCONSISTENT);
    assertThat(rows.get(0).errorArgs())
        .containsExactly(entry("column", "original"), entry("currencyColumn", "originalCurrency"));
    assertThat(rows.get(4).isParsed()).isTrue();
  }

  @Test
  void anOriginalAmountOrCurrencyColumnIsMappedOnlyWithTheOther() {
    for (MappingBuilder half :
        List.of(
            new MappingBuilder().bookingDate("date").amount("amount").originalAmount("original"),
            new MappingBuilder()
                .bookingDate("date")
                .amount("amount")
                .originalCurrency("originalCurrency"))) {
      ImportTemplateDefinition template = new Template().mapping(half.build()).build();
      assertThatThrownBy(() -> parser.validateTemplate(template, null))
          .isInstanceOf(ApiException.class)
          .hasMessageContaining("mapped together");
    }
  }

  @Test
  void cardStatementUsesSafeTypeFallbackAndRefundDescriptionPatterns() {
    ImportTemplateDefinition template =
        new Template()
            .delimiter(";")
            .decimal(",")
            .currencyMode("PER_ROW")
            .fixedCurrency(null)
            .mapping(
                new MappingBuilder()
                    .bookingDate("date")
                    .amount("amount")
                    .currency("currency")
                    .description("description")
                    .transactionType("type")
                    .originalAmount("original")
                    .originalCurrency("originalCurrency")
                    .exchangeRate("rate")
                    .build())
            .descriptionPatterns(Map.of("REFUND", "refund"))
            .build();

    byte[] statement =
        utf8(
            "date;amount;currency;description;type;original;originalCurrency;rate\n"
                + "2031-05-01;-55,00;CHF;Hotel charge;;-50,00;EUR;1,100000\n"
                + "2031-05-02;30,00;CHF;Monthly card payment;;;;\n"
                + "2031-05-03;8,00;CHF;SHOP ReFuNd;;;;\n"
                + "2031-05-04;4,00;CHF;Refund but explicitly paid;payment;;;\n");

    // The last row has an explicit type mapping, which wins over the description pattern.
    ImportTemplateDefinition mapped =
        new Template()
            .delimiter(";")
            .decimal(",")
            .currencyMode("PER_ROW")
            .fixedCurrency(null)
            .mapping(template.columnMapping())
            .typeMapping(Map.of("payment", "SETTLEMENT"))
            .descriptionPatterns(template.descriptionTypePatterns())
            .build();
    List<ParsedImportRow> card = parser.parse(statement, mapped, "CHF", true).rows();

    assertThat(card).allMatch(ParsedImportRow::isParsed).hasSize(4);
    assertThat(card)
        .extracting(row -> row.canonical().transactionType())
        .containsExactly("CREDIT_CARD_PURCHASE", "SETTLEMENT", "REFUND", "SETTLEMENT");
    assertThat(card.get(0).canonical().originalAmount()).isEqualByComparingTo("-50.00");
    assertThat(card.get(0).canonical().originalCurrency()).isEqualTo("EUR");
    assertThat(card.get(0).canonical().bankExchangeRate()).isEqualByComparingTo("1.100000");

    assertThat(parser.parse(statement, mapped, "CHF").rows().get(0).canonical().transactionType())
        .isEqualTo("EXPENSE");
  }

  @Test
  void ambiguousCardDescriptionPatternsAreErrorsAndInvalidPatternsAreRejected() {
    ImportTemplateDefinition template =
        new Template().descriptionPatterns(Map.of("REFUND", "refund", "SETTLEMENT", "ref")).build();
    List<ParsedImportRow> rows =
        parser
            .parse(
                utf8("date,amount,text\n2031-05-01,8.00,Refund received\n"), template, "CHF", true)
            .rows();

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).isError()).isTrue();
    assertThat(rows.get(0).errorCode()).isEqualTo(ImportRowErrorValues.TYPE_AMBIGUOUS);

    ImportTemplateDefinition invalid =
        new Template().descriptionPatterns(Map.of("REFUND", "(invalid")).build();
    assertThatThrownBy(() -> parser.validateTemplate(invalid, null))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("pattern");
  }

  @Test
  void anExchangeRateReadsWithTheTemplatesSeparatorsAndALongCellIsARowError() {
    ImportTemplateDefinition template =
        new Template()
            .delimiter(";")
            .decimal(",")
            .thousands(".")
            .mapping(
                new MappingBuilder()
                    .bookingDate("date")
                    .amount("amount")
                    .description("text")
                    .originalAmount("original")
                    .originalCurrency("originalCurrency")
                    .exchangeRate("rate")
                    .build())
            .build();

    List<ParsedImportRow> rows =
        parser
            .parse(
                utf8(
                    "date;amount;text;original;originalCurrency;rate\n"
                        + "2031-05-01;-10,00;Seoul;-14.501,20;KRW;1.450,12\n"
                        + "2031-05-02;-10,00;Long;-11,00;USD;"
                        + "1".repeat(60)
                        + "\n"
                        + "2031-05-03;-10,00;Zero;-11,00;USD;0,00\n"),
                template,
                "CHF",
                true)
            .rows();

    assertThat(rows.get(0).isParsed()).isTrue();
    assertThat(rows.get(0).canonical().bankExchangeRate()).isEqualByComparingTo("1450.12");
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.EXCHANGE_RATE_OUT_OF_RANGE);
    assertThat(rows.get(2).errorCode()).isEqualTo(ImportRowErrorValues.EXCHANGE_RATE_OUT_OF_RANGE);
    assertThat(rows.get(2).errorArgs())
        .containsExactly(entry("column", "rate"), entry("value", "0,00"));
  }

  @Test
  void anAmbiguousDescriptionNamesTheDescriptionColumnNotTheTypeColumn() {
    ImportTemplateDefinition template =
        new Template()
            .mapping(
                new MappingBuilder()
                    .bookingDate("date")
                    .amount("amount")
                    .description("text")
                    .transactionType("kind")
                    .build())
            .descriptionPatterns(Map.of("REFUND", "refund", "SETTLEMENT", "ref"))
            .build();

    ParsedImportRow row =
        parser
            .parse(
                utf8("date,amount,text,kind\n2031-05-01,8.00,Refund received,\n"),
                template,
                "CHF",
                true)
            .rows()
            .get(0);

    assertThat(row.errorCode()).isEqualTo(ImportRowErrorValues.TYPE_AMBIGUOUS);
    assertThat(row.errorArgs()).containsEntry("column", "text");
  }

  // --- separators, quoting, encodings ------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {",", ";", "|", "\t"})
  void readsEveryDelimiter(String delimiter) {
    String csv =
        String.join(delimiter, "date", "amount", "text")
            + "\n"
            + String.join(delimiter, "2026-03-01", "-7.50", "Coffee")
            + "\n";

    ImportParseResult result =
        parser.parse(utf8(csv), new Template().delimiter(delimiter).build(), null);

    assertThat(single(result).amount()).isEqualTo(new BigDecimal("-7.50"));
    assertThat(single(result).description()).isEqualTo("Coffee");
  }

  @Test
  void quotedCellsMayHoldTheDelimiterQuotesAndLineBreaks() {
    String csv = "date,amount,text\n2026-03-01,-7.50,\"Caf\u00e9, \"\"Corner\"\"\nsecond line\"\n";

    CanonicalImportRow row = single(parser.parse(utf8(csv), new Template().build(), null));

    assertThat(row.description()).isEqualTo("Caf\u00e9, \"Corner\"\nsecond line");
  }

  @Test
  void anUnclosedQuoteRejectsTheFile() {
    String csv = "date,amount,text\n2026-03-01,-7.50,\"open\n";

    assertFileError(utf8(csv), new Template().build(), ApiErrorCode.IMPORT_FILE_MALFORMED);
  }

  @Test
  void readsIso88591AndWindows1252() {
    String csv = "date,amount,text\n2026-03-01,-7.50,M\u00fcller \u20ac\n";

    CanonicalImportRow windows =
        single(
            parser.parse(
                csv.getBytes(Charset.forName("windows-1252")),
                new Template().encoding("windows-1252").build(),
                null));
    CanonicalImportRow latin =
        single(
            parser.parse(
                "date,amount,text\n2026-03-01,-7.50,M\u00fcller\n"
                    .getBytes(StandardCharsets.ISO_8859_1),
                new Template().encoding("ISO-8859-1").build(),
                null));

    assertThat(windows.description()).isEqualTo("M\u00fcller \u20ac");
    assertThat(latin.description()).isEqualTo("M\u00fcller");
  }

  @Test
  void aLatin1HeaderReadAsUtf8IsAnEncodingSuspect() {
    byte[] content =
        "Datum,Betrag,W\u00e4hrung\n2026-03-01,-7.50,CHF\n".getBytes(StandardCharsets.ISO_8859_1);

    assertFileError(content, new Template().build(), ApiErrorCode.IMPORT_ENCODING_SUSPECT);
  }

  @Test
  void anUnknownEncodingIsATemplateError() {
    assertTemplateInvalid(new Template().encoding("UTF-16").build(), "encoding");
  }

  // --- rows to skip ------------------------------------------------------------------------

  @Test
  void skipsPreambleLinesHeaderOffsetBlankLinesAndSummaryRows() {
    String csv =
        "Account: DE00 0000\r\n\r\nPeriod: March\r\n"
            + "ignored,record\r\n"
            + "date,amount,text\r\n"
            + "2026-03-01,-7.50,Coffee\r\n"
            + ",,\r\n"
            + "\r\n"
            + "2026-03-02,10.00,Refund\r\n"
            + "Balance,2.50,\r\n";
    ImportTemplateDefinition template =
        new Template().preamble(3).headerRowIndex(1).trailing(1).build();

    ImportParseResult result = parser.parse(utf8(csv), template, null);

    assertThat(canonical(result))
        .extracting(CanonicalImportRow::description)
        .containsExactly("Coffee", "Refund");
    assertThat(result.rows()).extracting(ParsedImportRow::rowNumber).containsExactly(1, 2);
  }

  @Test
  void aFileWithoutHeaderRowIsMappedByIndex() {
    String csv = "2026-03-01;Coffee;-7.50\n2026-03-02;Refund;10.00\n";
    ImportTemplateDefinition template =
        new Template()
            .delimiter(";")
            .headerRowIndex(-1)
            .mapping(mapping("0", "2").description("1").build())
            .build();

    ImportParseResult result = parser.parse(utf8(csv), template, null);

    assertThat(result.headerColumns()).isEmpty();
    assertThat(result.headerFingerprint()).isNull();
    assertThat(result.rows().get(0).rawData()).containsEntry("0", "2026-03-01");
    assertThat(canonical(result))
        .extracting(CanonicalImportRow::amount)
        .containsExactly(new BigDecimal("-7.50"), new BigDecimal("10.00"));
  }

  @Test
  void emptyFilesAndFilesWithoutDataRowsAreRejected() {
    ImportTemplateDefinition template = new Template().build();

    assertFileError(new byte[0], template, ApiErrorCode.IMPORT_FILE_EMPTY);
    assertFileError(utf8("\n\n,,\n"), template, ApiErrorCode.IMPORT_FILE_EMPTY);
    assertFileError(
        utf8("only\npreamble\n"),
        new Template().preamble(3).build(),
        ApiErrorCode.IMPORT_FILE_EMPTY);
    assertFileError(utf8("date,amount,text\n"), template, ApiErrorCode.IMPORT_FILE_NO_DATA_ROWS);
    assertFileError(
        utf8("date,amount,text\n2026-03-01,1.00,Summary\n"),
        new Template().trailing(1).build(),
        ApiErrorCode.IMPORT_FILE_NO_DATA_ROWS);
  }

  @Test
  void moreThanTheRowLimitIsRejected() {
    StringBuilder csv = new StringBuilder("date,amount,text\n");
    for (int i = 0; i <= ImportFileParserService.MAX_DATA_ROWS; i++) {
      csv.append("2026-03-01,1.00,x\n");
    }

    ApiException error =
        assertFileError(
            utf8(csv.toString()), new Template().build(), ApiErrorCode.IMPORT_FILE_TOO_MANY_ROWS);
    assertThat(error.getBody().getProperties())
        .containsEntry("maxRows", ImportFileParserService.MAX_DATA_ROWS);
  }

  @Test
  void exactlyTheRowLimitIsAccepted() {
    StringBuilder csv = new StringBuilder("date,amount,text\n");
    for (int i = 0; i < ImportFileParserService.MAX_DATA_ROWS; i++) {
      csv.append("2026-03-01,1.00,x\n");
    }

    assertThat(parser.parse(utf8(csv.toString()), new Template().build(), null).rows())
        .hasSize(ImportFileParserService.MAX_DATA_ROWS);
  }

  // --- header and mapping ------------------------------------------------------------------

  @Test
  void aHeaderLackingMappedColumnsRejectsTheWholeFile() {
    ImportTemplateDefinition template =
        new Template()
            .mapping(mapping("date", "amount").description("memo").notes("note").build())
            .build();

    ApiException error =
        assertFileError(
            utf8("date,amount,text\n2026-03-01,1.00,x\n"),
            template,
            ApiErrorCode.IMPORT_TEMPLATE_MISMATCH);

    assertThat(error.getBody().getProperties())
        .containsEntry("missingColumns", List.of("memo", "note"));
  }

  @Test
  void headerNamesMatchTrimmedAndIgnoringCase() {
    String csv = " DATE , Amount ,TEXT\n2026-03-01,1.00,x\n";

    assertThat(single(parser.parse(utf8(csv), new Template().build(), null)).description())
        .isEqualTo("x");
  }

  @Test
  void aRepeatedHeaderNameCanOnlyBeMappedByIndex() {
    String csv = "date,amount,text,amount\n2026-03-01,1.00,x,2.00\n";

    assertFileError(utf8(csv), new Template().build(), ApiErrorCode.IMPORT_TEMPLATE_MISMATCH);

    ImportParseResult byIndex =
        parser.parse(
            utf8(csv),
            new Template().mapping(mapping("date", "3").description("text").build()).build(),
            null);
    assertThat(single(byIndex).amount()).isEqualTo(new BigDecimal("2.00"));
    assertThat(byIndex.rows().get(0).rawData())
        .containsExactly(
            Map.entry("date", "2026-03-01"),
            Map.entry("amount", "1.00"),
            Map.entry("text", "x"),
            Map.entry("amount#3", "2.00"));
  }

  @Test
  void fingerprintIgnoresCaseAndSurroundingSpaceButNotOrder() {
    String fingerprint = ImportFileParserService.fingerprint(List.of("Datum", "Betrag"));

    assertThat(fingerprint).hasSize(64).matches("[0-9a-f]+");
    assertThat(ImportFileParserService.fingerprint(List.of(" DATUM ", "betrag")))
        .isEqualTo(fingerprint);
    assertThat(ImportFileParserService.fingerprint(List.of("Betrag", "Datum")))
        .isNotEqualTo(fingerprint);
    assertThat(ImportFileParserService.fingerprint(List.of("DatumBetrag")))
        .isNotEqualTo(fingerprint);
  }

  @Test
  void readHeaderReturnsTheHeaderCellsForDetection() {
    assertThat(
            parser.readHeader(
                utf8("pre\ndate;amount\n"), new Template().preamble(1).delimiter(";").build()))
        .containsExactly("date", "amount");
  }

  /** Detection reads the file once per template: nothing below the header is parsed. */
  @Test
  void readHeaderStopsAtTheHeaderRow() {
    byte[] content = utf8("date;amount\n2026-03-01;\"unclosed\n");
    ImportTemplateDefinition template = new Template().delimiter(";").build();

    assertThat(parser.readHeader(content, template)).containsExactly("date", "amount");
    assertFileError(content, template, ApiErrorCode.IMPORT_FILE_MALFORMED);
  }

  @Test
  void missingColumnsListsAbsentAndAmbiguousColumns() {
    ImportColumnMapping columns = mapping("date", "amount").description("text").build();

    assertThat(parser.missingColumns(columns, List.of("date", "amount", "text"))).isEmpty();
    assertThat(parser.missingColumns(columns, List.of("date", "amount", "amount")))
        .containsExactly("amount", "text");
  }

  // --- amounts -----------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        "1'234.50|.|'|1234.50",
        "1\u2019234.50|.|'|1234.50",
        "-1'000'000.05|.|'|-1000000.05",
        "1.234,50|,|.|1234.50",
        "-1.234,50|,|.|-1234.50",
        "+12|,|.|12",
        "1\u00a0234,50|,|\" \"|1234.50",
        "1\u202f234,50|,|\" \"|1234.50",
        ",50|,|.|0.50",
        "1234|.|'|1234",
        "12.5|.||12.5",
        "\u22125.00|.||-5.00",
        "- 5.00|.||-5.00",
        "0.00|.||0.00"
      })
  void parsesAmountsExactly(String value, String decimal, String thousands, String expected) {
    ImportTemplateDefinition template =
        new Template().decimal(decimal).thousands(thousands).build();

    assertThat(ImportFileParserService.parseAmount(value, "amount", template, false))
        .isEqualTo(new BigDecimal(expected));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        "12,3,4|,|.",
        "1.2.3,00|,|.",
        "12.5|,|.",
        "1'23.00|.|'",
        "abc|.|'",
        "12,|,|.",
        "--5|.|'",
        "5-|.|'",
        "-|.|'",
        "1e5|.|'"
      })
  void rejectsMalformedAmounts(String value, String decimal, String thousands) {
    String csv = "date;amount;text\n2026-03-01;" + value + ";x\n";

    ParsedImportRow row =
        parser
            .parse(
                utf8(csv),
                new Template().delimiter(";").decimal(decimal).thousands(thousands).build(),
                null)
            .rows()
            .get(0);

    assertThat(row.status()).isEqualTo(ImportRowErrorValues.STATUS_ERROR);
    assertThat(row.errorCode()).isEqualTo(ImportRowErrorValues.AMOUNT_UNPARSEABLE);
    assertThat(row.errorArgs()).containsEntry("column", "amount").containsEntry("value", value);
    assertThat(row.rawData()).containsEntry("amount", value);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "9'999'999'999'999'999.9999|9999999999999999.9999",
        "-0'000'000'000'000'000'001.50|-1.50",
        "1.500000|1.5000",
        "0.12340|0.1234"
      })
  void amountsWithinNumeric20Scale4AreAccepted(String value, String expected) {
    ImportTemplateDefinition template = new Template().thousands("'").build();

    assertThat(ImportFileParserService.parseAmount(value, "amount", template, false))
        .isEqualTo(new BigDecimal(expected));
  }

  @ParameterizedTest
  @ValueSource(strings = {"10'000'000'000'000'000.00", "12345678901234567", "1.23456", "0.00001"})
  void amountsOutsideNumeric20Scale4AreOutOfRange(String value) {
    String csv = "date;amount;text\n2026-03-01;" + value + ";x\n";

    ParsedImportRow row =
        parser
            .parse(utf8(csv), new Template().delimiter(";").thousands("'").build(), null)
            .rows()
            .get(0);

    assertThat(row.errorCode()).isEqualTo(ImportRowErrorValues.AMOUNT_OUT_OF_RANGE);
    assertThat(row.errorArgs()).containsEntry("column", "amount").containsEntry("value", value);
  }

  /** A huge cell is refused by its length, never handed to BigDecimal (quadratic, minutes). */
  @Test
  void aMegabyteLongAmountIsRejectedQuickly() {
    String csv = "date,amount,text\n2026-03-01," + "1".repeat(2_000_000) + ",x\n";

    ParsedImportRow row =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> parser.parse(utf8(csv), new Template().build(), null).rows().get(0));

    assertThat(row.errorCode()).isEqualTo(ImportRowErrorValues.AMOUNT_OUT_OF_RANGE);
    assertThat(row.errorArgs().get("value")).hasSize(100);
  }

  @Test
  void negativeInParenthesesReadsBothForms() {
    String csv =
        "date;amount;text\n2026-03-01;(1'234.50);a\n2026-03-02;12.00;b\n2026-03-03;-5.00;c\n";
    ImportTemplateDefinition template =
        new Template()
            .delimiter(";")
            .thousands("'")
            .amountRepresentation("NEGATIVE_IN_PARENTHESES")
            .build();

    assertThat(canonical(parser.parse(utf8(csv), template, null)))
        .extracting(CanonicalImportRow::amount)
        .containsExactly(
            new BigDecimal("-1234.50"), new BigDecimal("12.00"), new BigDecimal("-5.00"));
  }

  @Test
  void separateDebitAndCreditTakeTheSignFromTheColumn() {
    String csv =
        "date;debit;credit\n"
            + "2026-03-01;12,50;\n"
            + "2026-03-02;-12,50;\n"
            + "2026-03-03;;7,00\n"
            + "2026-03-04;0,00;7,00\n"
            + "2026-03-05;3,00;0,00\n";
    ImportTemplateDefinition template = debitCreditTemplate();

    assertThat(canonical(parser.parse(utf8(csv), template, null)))
        .extracting(CanonicalImportRow::amount)
        .containsExactly(
            new BigDecimal("-12.50"),
            new BigDecimal("-12.50"),
            new BigDecimal("7.00"),
            new BigDecimal("7.00"),
            new BigDecimal("-3.00"));
  }

  @Test
  void separateDebitAndCreditRejectBothOrNeitherSide() {
    String csv = "date;debit;credit\n2026-03-01;12,50;7,00\n2026-03-02;;\n";

    List<ParsedImportRow> rows = parser.parse(utf8(csv), debitCreditTemplate(), null).rows();

    assertThat(rows.get(0).errorCode()).isEqualTo(ImportRowErrorValues.AMOUNT_BOTH_SIDES);
    assertThat(rows.get(0).errorArgs())
        .containsExactly(Map.entry("debitColumn", "debit"), Map.entry("creditColumn", "credit"));
    // Two absent amounts are still invalid; they must never become a silent skipped booking.
    assertThat(rows.get(1).isError()).isTrue();
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.VALUE_MISSING);
    assertThat(rows.get(1).errorArgs()).containsEntry("column", "debit / credit");
  }

  @Test
  void separateDebitAndCreditSkipExplicitZeroAmountsButKeepNonzeroRows() {
    String csv =
        "date;debit;credit\n"
            + "2026-03-01;0,00;\n"
            + "2026-03-02;;0,00\n"
            + "2026-03-03;0,00;0,00\n"
            + "2026-03-04;-0,0000;\n"
            + "2026-03-05;0,00;7,00\n"
            + "2026-03-06;3,00;0,00\n";

    List<ParsedImportRow> rows = parser.parse(utf8(csv), debitCreditTemplate(), null).rows();

    assertThat(rows).hasSize(6);
    for (int i = 0; i < 4; i++) {
      ParsedImportRow row = rows.get(i);
      assertThat(row.rowNumber()).isEqualTo(i + 1);
      assertThat(row.isSkipped()).isTrue();
      assertThat(row.isError()).isFalse();
      assertThat(row.errorCode()).isEqualTo(ImportRowErrorValues.ZERO_AMOUNT);
      assertThat(row.errorArgs()).isEmpty();
      assertThat(row.canonical().amount()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(row.rawData()).containsKeys("debit", "credit");
    }
    // The negative zero keeps its four decimal places.
    assertThat(rows.get(3).canonical().amount()).isEqualTo(new BigDecimal("0.0000"));
    assertThat(rows.get(4).isParsed()).isTrue();
    assertThat(rows.get(4).canonical().amount()).isEqualByComparingTo("7.00");
    assertThat(rows.get(5).isParsed()).isTrue();
    assertThat(rows.get(5).canonical().amount()).isEqualByComparingTo("-3.00");
  }

  /** DoD: formatted amounts parse back to exactly the same BigDecimal, scale included. */
  @Test
  void formattedAmountsRoundTripExactly() {
    Random random = new Random(229);
    String[][] separators = {{".", null}, {".", "'"}, {".", "\u2019"}, {",", "."}, {",", " "}};
    for (int i = 0; i < 2_000; i++) {
      BigDecimal amount =
          new BigDecimal(new BigInteger(53, random), random.nextInt(5))
              .multiply(random.nextBoolean() ? BigDecimal.ONE : BigDecimal.ONE.negate());
      String[] pair = separators[i % separators.length];
      ImportTemplateDefinition template =
          new Template().decimal(pair[0]).thousands(pair[1]).build();

      BigDecimal parsed =
          ImportFileParserService.parseAmount(
              format(amount, pair[0], pair[1]), "amount", template, false);

      assertThat(parsed).as("%s with %s", amount, Arrays.toString(pair)).isEqualTo(amount);
    }
  }

  // --- dates, currencies, types ------------------------------------------------------------

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        "dd.MM.yyyy|31.12.2026|2026-12-31",
        "dd.MM.uuuu|01.02.2026|2026-02-01",
        "yyyy-MM-dd|2026-02-28|2026-02-28",
        "MM/dd/yyyy|02/28/2026|2026-02-28",
        "dd.MM.yy|05.03.26|2026-03-05",
        "yyyyMMdd|20260305|2026-03-05"
      })
  void parsesDatePatterns(String pattern, String value, String expected) {
    String csv = "date;amount;text\n" + value + ";1.00;x\n";

    CanonicalImportRow row =
        single(
            parser.parse(
                utf8(csv), new Template().delimiter(";").dateFormat(pattern).build(), null));

    assertThat(row.bookingDate()).isEqualTo(LocalDate.parse(expected));
  }

  @Test
  void anImpossibleDateIsARowErrorNamingThePattern() {
    String csv = "date;amount;text\n31.02.2026;1.00;x\n01.03.2026;2.00;y\n";

    ImportParseResult result =
        parser.parse(
            utf8(csv), new Template().delimiter(";").dateFormat("dd.MM.yyyy").build(), null);

    ParsedImportRow rejected = result.rows().get(0);
    assertThat(rejected.errorCode()).isEqualTo(ImportRowErrorValues.DATE_UNPARSEABLE);
    assertThat(rejected.errorArgs())
        .containsExactly(
            Map.entry("column", "date"),
            Map.entry("value", "31.02.2026"),
            Map.entry("pattern", "dd.MM.yyyy"));
    assertThat(result.rows().get(1).isParsed()).as("the next row still parses").isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "dd.MM", "HH:mm", "not a pattern {", "dd.MM.yyyy'"})
  void anInvalidDatePatternIsATemplateError(String pattern) {
    assertTemplateInvalid(new Template().dateFormat(pattern).build(), "dateFormat");
  }

  @Test
  void currencyModesFixedPerRowAndFromAccount() {
    String csv = "date,amount,text,ccy\n2026-03-01,1.00,x,eur\n2026-03-02,1.00,y,XYZ\n";
    ImportColumnMapping perRow =
        mapping("date", "amount").description("text").currency("ccy").build();

    List<ParsedImportRow> rows =
        parser
            .parse(
                utf8(csv),
                new Template().currencyMode("PER_ROW").fixedCurrency(null).mapping(perRow).build(),
                null)
            .rows();
    String fixed =
        parser.parse(utf8(csv), new Template().build(), "USD").rows().get(0).canonical().currency();
    ImportTemplateDefinition fromAccount =
        new Template().currencyMode("FROM_ACCOUNT").fixedCurrency(null).build();

    assertThat(rows.get(0).canonical().currency()).isEqualTo("EUR");
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.CURRENCY_INVALID);
    assertThat(rows.get(1).errorArgs()).containsEntry("value", "XYZ");
    assertThat(fixed).as("FIXED ignores the account").isEqualTo("CHF");
    assertThat(parser.parse(utf8(csv), fromAccount, "USD").rows().get(0).canonical().currency())
        .isEqualTo("USD");
    assertThat(parser.parse(utf8(csv), fromAccount, null).rows().get(0).canonical().currency())
        .isNull();
  }

  @Test
  void transactionTypesFromMappingCanonicalNameOrSign() {
    String csv =
        "date,amount,type\n"
            + "2026-03-01,-1.00, lastschrift \n"
            + "2026-03-02,-1.00,fee\n"
            + "2026-03-03,-1.00,BUY\n"
            + "2026-03-04,-1.00,Unknown\n"
            + "2026-03-05,0.00,\n";
    ImportTemplateDefinition template =
        new Template()
            .mapping(mapping("date", "amount").transactionType("type").build())
            .typeMapping(Map.of("LASTSCHRIFT", "DEBT_REPAYMENT"))
            .build();

    List<ParsedImportRow> rows = parser.parse(utf8(csv), template, null).rows();

    assertThat(rows.get(0).canonical().transactionType()).isEqualTo("DEBT_REPAYMENT");
    assertThat(rows.get(1).canonical().transactionType()).isEqualTo("FEE");
    assertThat(rows.get(2).errorCode()).isEqualTo(ImportRowErrorValues.TYPE_NOT_ALLOWED);
    assertThat(rows.get(2).errorArgs())
        .containsEntry("column", "type")
        .containsEntry("value", "BUY");
    assertThat(rows.get(3).canonical().transactionType()).isEqualTo("EXPENSE");
    assertThat(rows.get(4).canonical().transactionType()).isEqualTo("INCOME");
  }

  @Test
  void mccMustBeFourDigits() {
    String csv = "date,amount,mcc\n2026-03-01,-1.00,5411\n2026-03-02,-1.00,541\n";
    ImportTemplateDefinition template =
        new Template().mapping(mapping("date", "amount").mcc("mcc").build()).build();

    List<ParsedImportRow> rows = parser.parse(utf8(csv), template, null).rows();

    assertThat(rows.get(0).canonical().mcc()).isEqualTo("5411");
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.MCC_INVALID);
  }

  @Test
  void shortRowsAndEmptyRequiredCellsAreRowErrors() {
    String csv = "date,amount,text\n2026-03-01\n,1.00,x\n2026-03-02,1.00\n";

    List<ParsedImportRow> rows = parser.parse(utf8(csv), new Template().build(), null).rows();

    assertThat(rows.get(0).errorCode()).isEqualTo(ImportRowErrorValues.COLUMN_MISSING);
    assertThat(rows.get(0).errorArgs()).containsEntry("column", "amount");
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.VALUE_MISSING);
    assertThat(rows.get(1).errorArgs()).containsEntry("column", "date");
    assertThat(rows.get(2).canonical().description()).as("an optional cell may be absent").isNull();
  }

  @Test
  void echoedValuesAreTruncated() {
    String longValue = "x".repeat(300);
    String csv = "date,amount,text\n2026-03-01," + longValue + ",y\n";

    ParsedImportRow row = parser.parse(utf8(csv), new Template().build(), null).rows().get(0);

    assertThat(row.errorArgs().get("value")).hasSize(100);
    assertThat(row.rawData().get("amount")).as("the raw row keeps everything").hasSize(300);
  }

  // --- template validation -----------------------------------------------------------------

  @Test
  void onlyCashTransactionsWithAUserSelectedAccountAreSupported() {
    for (ImportTemplateDefinition template :
        List.of(
            new Template().templateClass("SECURITIES_TRANSACTIONS").build(),
            new Template().templateClass("SNAPSHOT").build(),
            new Template().accountStrategy("COLUMN").build())) {
      ApiException error =
          catchThrowableOfType(ApiException.class, () -> parser.validateTemplate(template, null));
      assertThat(error.getCode()).isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_UNSUPPORTED);
      assertThat(error.getStatusCode().value()).isEqualTo(422);
    }
  }

  @Test
  void templateRulesNameTheField() {
    assertTemplateInvalid(
        new Template().amountRepresentation("NET").build(), "amountRepresentation");
    assertTemplateInvalid(new Template().currencyMode("ANY").build(), "currencyMode");
    assertTemplateInvalid(new Template().delimiter("\"").build(), "delimiter");
    assertTemplateInvalid(new Template().delimiter(";;").build(), "delimiter");
    assertTemplateInvalid(new Template().decimal("1").build(), "decimalSeparator");
    assertTemplateInvalid(new Template().decimal(",").thousands(",").build(), "thousandsSeparator");
    assertTemplateInvalid(new Template().fixedCurrency("XX").build(), "fixedCurrency");
    assertTemplateInvalid(new Template().currencyMode("PER_ROW").build(), "fixedCurrency");
    assertTemplateInvalid(new Template().mapping(null).build(), "columnMapping");
    assertTemplateInvalid(
        new Template().mapping(mapping(null, "amount").build()).build(),
        "columnMapping.bookingDate");
    assertTemplateInvalid(
        new Template().mapping(mapping("date", null).build()).build(), "columnMapping.amount");
    assertTemplateInvalid(
        new Template().amountRepresentation("SEPARATE_DEBIT_CREDIT").build(),
        "columnMapping.debitAmount");
    assertTemplateInvalid(
        new Template().currencyMode("PER_ROW").fixedCurrency(null).build(),
        "columnMapping.currency");
    assertTemplateInvalid(new Template().headerRowIndex(-1).build(), "columnMapping.bookingDate");
    assertTemplateInvalid(new Template().headerRowIndex(-3).build(), "headerRowIndex");
    assertTemplateInvalid(new Template().headerRowIndex(101).build(), "headerRowIndex");
    assertTemplateInvalid(new Template().preamble(-1).build(), "preambleRowCount");
    assertTemplateInvalid(new Template().preamble(101).build(), "preambleRowCount");
    assertTemplateInvalid(new Template().trailing(-5).build(), "trailingSummaryRowCount");
    assertTemplateInvalid(new Template().trailing(101).build(), "trailingSummaryRowCount");
    assertTemplateInvalid(new Template().typeMapping(Map.of("Kauf", "BUY")).build(), "typeMapping");
    assertTemplateInvalid(new Template().typeMapping(Map.of(" ", "FEE")).build(), "typeMapping");
    Map<String, String> clash = new HashMap<>();
    clash.put("Geb\u00fchr", "FEE");
    clash.put("GEB\u00dcHR", "TAX");
    assertTemplateInvalid(new Template().typeMapping(clash).build(), "typeMapping");
    Map<String, String> nullType = new HashMap<>();
    nullType.put("Geb\u00fchr", null);
    assertTemplateInvalid(new Template().typeMapping(nullType).build(), "typeMapping");
  }

  @Test
  void mappingByNameIsCheckedAgainstTheSampleHeader() {
    ImportTemplateDefinition template = new Template().build();

    parser.validateTemplate(template, List.of("Date", "Amount", "Text"));
    assertThatThrownBy(() -> parser.validateTemplate(template, List.of("date", "amount")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_INVALID);
              assertThat(e.getBody().getProperties())
                  .containsEntry("field", "columnMapping.description");
            });
    assertThatThrownBy(
            () -> parser.validateTemplate(template, List.of("date", "amount", "text", "TEXT")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getBody().getDetail()).contains("more than once"));
  }

  // --- helpers ------------------------------------------------------------------------------

  private ApiException assertFileError(
      byte[] content, ImportTemplateDefinition template, String code) {
    ApiException error =
        catchThrowableOfType(ApiException.class, () -> parser.parse(content, template, null));
    assertThat(error).as("expected %s", code).isNotNull();
    assertThat(error.getCode()).isEqualTo(code);
    assertThat(error.getStatusCode().value()).isEqualTo(422);
    return error;
  }

  private void assertTemplateInvalid(ImportTemplateDefinition template, String field) {
    ApiException error =
        catchThrowableOfType(ApiException.class, () -> parser.validateTemplate(template, null));
    assertThat(error).as("expected %s to be rejected", field).isNotNull();
    assertThat(error.getCode()).isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_INVALID);
    assertThat(error.getBody().getProperties()).containsEntry("field", field);
  }

  private static ImportTemplateDefinition germanTemplate() {
    return new Template()
        .delimiter(";")
        .decimal(",")
        .thousands(".")
        .dateFormat("dd.MM.yyyy")
        .preamble(4)
        .trailing(1)
        .amountRepresentation("SEPARATE_DEBIT_CREDIT")
        .currencyMode("PER_ROW")
        .fixedCurrency(null)
        .mapping(
            new MappingBuilder()
                .bookingDate("Buchungstag")
                .valueDate("Wertstellung")
                .debit("Soll")
                .credit("Haben")
                .currency("W\u00e4hrung")
                .transactionType("Buchungstext")
                .counterparty("Auftraggeber / Beg\u00fcnstigter")
                .description("Verwendungszweck")
                .build())
        .typeMapping(
            Map.of(
                "Lastschrift", "EXPENSE",
                "Gutschrift", "INCOME",
                "Entgelt", "FEE",
                "Zinsen", "INTEREST"))
        .build();
  }

  private static ImportTemplateDefinition debitCreditTemplate() {
    return new Template()
        .delimiter(";")
        .decimal(",")
        .thousands(".")
        .amountRepresentation("SEPARATE_DEBIT_CREDIT")
        .mapping(new MappingBuilder().bookingDate("date").debit("debit").credit("credit").build())
        .build();
  }

  // amount written with the given separators, thousands grouped by three.
  private static String format(BigDecimal amount, String decimal, String thousands) {
    String plain = amount.abs().toPlainString();
    int dot = plain.indexOf('.');
    String integer = dot < 0 ? plain : plain.substring(0, dot);
    StringBuilder grouped = new StringBuilder();
    for (int i = 0; i < integer.length(); i++) {
      if (thousands != null && i > 0 && (integer.length() - i) % 3 == 0) {
        grouped.append(thousands);
      }
      grouped.append(integer.charAt(i));
    }
    String fraction = dot < 0 ? "" : decimal + plain.substring(dot + 1);
    return (amount.signum() < 0 ? "-" : "") + grouped + fraction;
  }

  private static CanonicalImportRow row(
      String bookingDate,
      String valueDate,
      String amount,
      String currency,
      String type,
      String description) {
    return new CanonicalImportRow(
        LocalDate.parse(bookingDate),
        LocalDate.parse(valueDate),
        new BigDecimal(amount),
        currency,
        type,
        description,
        null,
        null,
        null,
        null,
        null);
  }

  private static List<CanonicalImportRow> canonical(ImportParseResult result) {
    return result.rows().stream().map(ParsedImportRow::canonical).toList();
  }

  private static CanonicalImportRow single(ImportParseResult result) {
    assertThat(result.rows()).hasSize(1);
    assertThat(result.rows().get(0).errorCode()).isNull();
    return result.rows().get(0).canonical();
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static byte[] fixture(String name) throws IOException {
    try (InputStream stream =
        ImportFileParserServiceTest.class.getResourceAsStream("/import/" + name)) {
      return stream.readAllBytes();
    }
  }

  static MappingBuilder mapping(String bookingDate, String amount) {
    return new MappingBuilder().bookingDate(bookingDate).amount(amount);
  }

  /** The test template: comma-separated, ISO dates, CHF, columns date/amount/text by default. */
  static final class Template {
    private String templateClass = "CASH_TRANSACTIONS";
    private String delimiter = ",";
    private String encoding = "UTF-8";
    private String decimal = ".";
    private String thousands;
    private String dateFormat = "yyyy-MM-dd";
    private int headerRowIndex;
    private int preamble;
    private int trailing;
    private String amountRepresentation = "SINGLE_SIGNED_COLUMN";
    private String currencyMode = "FIXED";
    private String fixedCurrency = "CHF";
    private ImportColumnMapping mapping =
        ImportFileParserServiceTest.mapping("date", "amount").description("text").build();
    private Map<String, String> typeMapping = Map.of();
    private Map<String, String> descriptionPatterns = Map.of();
    private ImportRowFilter rowFilter;
    private String accountStrategy = "USER_SELECTED";
    private String fileFormat = "CSV";
    private ImportPdfLayout pdfLayout;

    Template templateClass(String value) {
      templateClass = value;
      return this;
    }

    Template delimiter(String value) {
      delimiter = value;
      return this;
    }

    Template encoding(String value) {
      encoding = value;
      return this;
    }

    Template decimal(String value) {
      decimal = value;
      return this;
    }

    Template thousands(String value) {
      thousands = value;
      return this;
    }

    Template dateFormat(String value) {
      dateFormat = value;
      return this;
    }

    Template headerRowIndex(int value) {
      headerRowIndex = value;
      return this;
    }

    Template preamble(int value) {
      preamble = value;
      return this;
    }

    Template trailing(int value) {
      trailing = value;
      return this;
    }

    Template amountRepresentation(String value) {
      amountRepresentation = value;
      return this;
    }

    Template currencyMode(String value) {
      currencyMode = value;
      return this;
    }

    Template fixedCurrency(String value) {
      fixedCurrency = value;
      return this;
    }

    Template mapping(ImportColumnMapping value) {
      mapping = value;
      return this;
    }

    Template typeMapping(Map<String, String> value) {
      typeMapping = value;
      return this;
    }

    Template descriptionPatterns(Map<String, String> value) {
      descriptionPatterns = value;
      return this;
    }

    Template rowFilter(String column, List<String> accepted) {
      rowFilter = new ImportRowFilter(column, accepted);
      return this;
    }

    Template accountStrategy(String value) {
      accountStrategy = value;
      return this;
    }

    Template pdf(String format, ImportPdfLayout layout) {
      fileFormat = format;
      pdfLayout = layout;
      return this;
    }

    ImportTemplateDefinition build() {
      return new ImportTemplateDefinition(
          templateClass,
          delimiter,
          encoding,
          decimal,
          thousands,
          dateFormat,
          headerRowIndex,
          preamble,
          trailing,
          amountRepresentation,
          currencyMode,
          fixedCurrency,
          mapping,
          typeMapping,
          accountStrategy,
          fileFormat,
          pdfLayout,
          descriptionPatterns,
          rowFilter);
    }
  }

  static final class MappingBuilder {
    private final Map<String, String> fields = new HashMap<>();

    MappingBuilder bookingDate(String column) {
      fields.put("bookingDate", column);
      return this;
    }

    MappingBuilder valueDate(String column) {
      fields.put("valueDate", column);
      return this;
    }

    MappingBuilder amount(String column) {
      fields.put("amount", column);
      return this;
    }

    MappingBuilder debit(String column) {
      fields.put("debitAmount", column);
      return this;
    }

    MappingBuilder credit(String column) {
      fields.put("creditAmount", column);
      return this;
    }

    MappingBuilder currency(String column) {
      fields.put("currency", column);
      return this;
    }

    MappingBuilder description(String column) {
      fields.put("description", column);
      return this;
    }

    MappingBuilder counterparty(String column) {
      fields.put("counterpartyName", column);
      return this;
    }

    MappingBuilder transactionType(String column) {
      fields.put("transactionType", column);
      return this;
    }

    MappingBuilder originalAmount(String column) {
      fields.put("originalAmount", column);
      return this;
    }

    MappingBuilder originalCurrency(String column) {
      fields.put("originalCurrency", column);
      return this;
    }

    MappingBuilder exchangeRate(String column) {
      fields.put("exchangeRate", column);
      return this;
    }

    MappingBuilder mcc(String column) {
      fields.put("mcc", column);
      return this;
    }

    MappingBuilder notes(String column) {
      fields.put("notes", column);
      return this;
    }

    ImportColumnMapping build() {
      return new ImportColumnMapping(
          fields.get("bookingDate"),
          fields.get("valueDate"),
          fields.get("amount"),
          fields.get("debitAmount"),
          fields.get("creditAmount"),
          fields.get("currency"),
          fields.get("description"),
          fields.get("counterpartyName"),
          fields.get("externalId"),
          fields.get("transactionType"),
          fields.get("mcc"),
          fields.get("iso20022BankTransactionCode"),
          fields.get("notes"),
          fields.get("originalAmount"),
          fields.get("originalCurrency"),
          fields.get("exchangeRate"));
    }
  }
}
