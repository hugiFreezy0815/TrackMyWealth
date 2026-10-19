package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportHoldingsRule;
import com.trackmywealth.backend.dto.ImportHoldingsRule.ImportHoldingsColumns;
import com.trackmywealth.backend.dto.ImportHoldingsStatement;
import com.trackmywealth.backend.dto.ImportHoldingsStatement.Line;
import com.trackmywealth.backend.dto.ImportHoldingsValues;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-07-13 (#273): the holdings rule reads a Swissquote-layout PDF and the two PostFinance
 * spreadsheet exports, and fails closed on every line it cannot read. The PDF rule is the one V100
 * ships; the files are the invented {@link ShippedTemplateFixtures}, never a real statement.
 */
class ImportHoldingsReaderServiceTest {

  // V100's Swissquote rule, verbatim.
  static final ImportHoldingsRule SWISSQUOTE =
      new ImportHoldingsRule(
          "Portfolio-Wertentwicklung per",
          "^Portfolio-Wertentwicklung per (?P<date>[0-9]{2}[.][0-9]{2}[.][0-9]{4})$",
          null,
          "Bewertung in (?P<currency>[A-Z]{3})",
          List.of(
              "^(?P<quantity>[0-9][0-9'.]*) (?P<name>.+) (?P<isin>[A-Z]{2}[A-Z0-9]{9}[0-9])"
                  + " [0-9'.]+ (?P<price>[0-9'.]+) [0-9]{2}[.][0-9]{2}[.][0-9]{4}"
                  + " (?P<value>[0-9'.]+)(?: [0-9'.]+)? -?[0-9.]+$",
              "^(?P<symbol>[A-Z0-9]{2,10}) Position in [A-Z0-9]{2,10} (?P<quantity>[0-9'.]+)"
                  + " (?P<price>[0-9'.]+) (?P<value>[0-9'.]+) -?[0-9.]+$"),
          "^(?P<currency>[A-Z]{3}) [0-9]+ Kontokorrent (?:[(][A-Z]{3}[)]|[A-Z]{3})"
              + " (?P<amount>-?[0-9'.]+) [0-9.]+ -?[0-9'.]+ -?[0-9.]+$",
          null);
  static final ImportHoldingsColumns POSITIONS =
      new ImportHoldingsColumns(
          null, "Symbol", null, "Anzahl", "Preis", "Totalwert", "Währung", null);
  static final ImportHoldingsColumns CASH =
      new ImportHoldingsColumns(null, null, null, null, null, null, "Währung", "Kontosaldo");
  private static final LocalDate MEMBER_DATE = LocalDate.of(2031, 12, 31);

  private final PdfImportReaderService pdf =
      new PdfImportReaderService(mock(LocalOcrService.class));
  private final ImportHoldingsReaderService reader =
      new ImportHoldingsReaderService(pdf, new SpreadsheetImportReaderService());
  // A reader whose PDF text a test sets with stubText.
  private ImportHoldingsReaderService stubbed;

  // --- PDF ---------------------------------------------------------------------------------

  @Test
  void aSwissquoteStatementIsReadIntoItsDatePositionsAndCash() throws Exception {
    ImportHoldingsStatement statement =
        reader.read(ShippedTemplateFixtures.swissquoteHoldings(), pdfTemplate(SWISSQUOTE), null);

    assertThat(statement.date()).isEqualTo(LocalDate.of(2031, 12, 31));
    assertThat(statement.dateSource()).isEqualTo(ImportHoldingsValues.DATE_STATEMENT);
    assertThat(statement.linesOf(ImportHoldingsValues.KIND_CASH))
        .extracting(Line::currency, Line::amount)
        .containsExactly(
            tuple("CHF", new BigDecimal("1204.35")), tuple("USD", new BigDecimal("310.20")));
    // The section header's first currency is the positions' (USD, not the CHF countervalue);
    // the crypto line's symbol is kept for the workspace's ticker.
    assertThat(statement.linesOf(ImportHoldingsValues.KIND_POSITION))
        .extracting(
            Line::isin,
            Line::symbol,
            Line::name,
            Line::quantity,
            Line::price,
            Line::value,
            Line::currency)
        .containsExactly(
            tuple(
                null,
                "XQZ",
                null,
                new BigDecimal("12.5000000"),
                new BigDecimal("3.1415926"),
                new BigDecimal("39.27"),
                "CHF"),
            tuple(
                ShippedTemplateFixtures.HOLDING_ISIN_CHF,
                null,
                "INVENTED HOLDING AG N",
                new BigDecimal("25"),
                new BigDecimal("113.400"),
                new BigDecimal("2835.00"),
                "CHF"),
            tuple(
                ShippedTemplateFixtures.HOLDING_ISIN_USD,
                null,
                "EXAMPLE TECHNOLOGIES",
                new BigDecimal("1200"),
                new BigDecimal("4.370"),
                new BigDecimal("5244.00"),
                "USD"),
            tuple(
                ShippedTemplateFixtures.HOLDING_ISIN_FUND,
                null,
                "SAMPLE WORLD ETF",
                new BigDecimal("12.3456"),
                new BigDecimal("95.120"),
                new BigDecimal("1174.31"),
                "CHF"));
    assertThat(statement.linesOf(ImportHoldingsValues.KIND_ERROR)).isEmpty();
    assertThat(statement.lines()).allSatisfy(line -> assertThat(line.warnings()).isEmpty());
    assertThat(statement.lines()).extracting(Line::rowNumber).containsExactly(1, 2, 3, 4, 5, 6);
  }

  @Test
  void theDetectionNeedsTheMarkerAndAReadableDate() {
    ImportTemplateDefinition template = pdfTemplate(SWISSQUOTE);
    assertThat(
            ImportHoldingsReaderService.isStatementOf(
                "Portfolio-Wertentwicklung per 31.12.2031", template))
        .isTrue();
    assertThat(
            ImportHoldingsReaderService.isStatementOf(
                "Portfolio-Wertentwicklung per 31.13.2031", template))
        .isFalse();
    assertThat(ImportHoldingsReaderService.isStatementOf("Dein Kontoauszug", template)).isFalse();
  }

  @Test
  void aLineTakenForAPositionButUnreadableIsAnErrorLineNeverDropped() {
    stubText(
        "Portfolio-Wertentwicklung per 31.12.2031",
        "Anzahl Wertpapier ISIN Durchschnittspreis Marktpreis Kursdatum Bewertung in CHF %",
        // A wrong check digit.
        "25 BROKEN CHECK DIGIT ZZ0000000017 1.000 2.000 30.12.2031 50.00 1.00",
        // A value over NUMERIC(20,4).
        "1 HUGE VALUE ZZ0000000024 1.000 2.000 30.12.2031 12345678901234567.00 1.00",
        // The same ISIN a second time.
        "2 FIRST ZZ0000000032 1.000 2.000 30.12.2031 4.00 1.00",
        "3 AGAIN ZZ0000000032 1.000 2.000 30.12.2031 6.00 1.00");
    ImportHoldingsStatement statement = readStubbed(pdfTemplate(SWISSQUOTE));

    assertThat(statement.lines())
        .extracting(Line::kind, Line::errorCode)
        .containsExactly(
            tuple(ImportHoldingsValues.KIND_ERROR, ImportHoldingsValues.ERROR_IDENTIFIER_INVALID),
            tuple(ImportHoldingsValues.KIND_ERROR, ImportHoldingsValues.ERROR_VALUE_UNREADABLE),
            tuple(ImportHoldingsValues.KIND_POSITION, null),
            tuple(ImportHoldingsValues.KIND_ERROR, ImportHoldingsValues.ERROR_DUPLICATE));
    assertThat(statement.lines().get(0).rawText()).contains("BROKEN CHECK DIGIT");
  }

  @Test
  void aPositionWithoutAnyCurrencyIsAnErrorLine() {
    stubText("Portfolio-Wertentwicklung per 31.12.2031", "XQZ Position in XQZ 1.5 2.0 3.00 0.10");
    assertThat(readStubbed(pdfTemplate(SWISSQUOTE)).lines())
        .extracting(Line::errorCode)
        .containsExactly(ImportHoldingsValues.ERROR_CURRENCY_MISSING);
  }

  @Test
  void aValueThatIsNotQuantityTimesPriceIsWarnedAboutButABondInPercentIsNot() {
    stubText(
        "Portfolio-Wertentwicklung per 31.12.2031",
        "Anzahl Wertpapier ISIN Durchschnittspreis Marktpreis Kursdatum Bewertung in CHF %",
        "10 COLUMNS SWAPPED ZZ0000000016 1.000 50.000 30.12.2031 7.00 1.00",
        "5000 BOND IN PERCENT ZZ0000000024 99.000 101.250 30.12.2031 5'062.50 1.00");
    assertThat(readStubbed(pdfTemplate(SWISSQUOTE)).lines())
        .extracting(Line::warnings)
        .containsExactly(List.of(ImportHoldingsValues.WARNING_VALUE_MISMATCH), List.of());
  }

  @Test
  void aStatementWithoutAReadableDateOrWithoutAnyLineIsRefused() {
    stubText("Portfolio-Wertentwicklung per 2031-12-31");
    assertThatThrownBy(() -> readStubbed(pdfTemplate(SWISSQUOTE)))
        .isInstanceOfSatisfying(
            ImportFileRejectedException.class,
            e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_FILE_MALFORMED));
    stubText("Portfolio-Wertentwicklung per 31.12.2031", "Nothing held");
    assertThatThrownBy(() -> readStubbed(pdfTemplate(SWISSQUOTE)))
        .isInstanceOfSatisfying(
            ImportFileRejectedException.class,
            e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_FILE_NO_DATA_ROWS));
  }

  // --- spreadsheets ------------------------------------------------------------------------

  @Test
  void aPositionsWorkbookIsReadByTickerAndPassesOverHeadingsAndSubtotals() throws Exception {
    ImportHoldingsStatement statement =
        reader.read(
            ShippedTemplateFixtures.postfinancePositionsWorkbook(),
            sheetTemplate(POSITIONS),
            MEMBER_DATE);

    assertThat(statement.date()).isEqualTo(MEMBER_DATE);
    assertThat(statement.dateSource()).isEqualTo(ImportHoldingsValues.DATE_MEMBER);
    // A number cell is a double: 5244.000000000001 is the 5244 the export shows.
    assertThat(statement.lines())
        .extracting(Line::symbol, Line::quantity, Line::price, Line::value, Line::currency)
        .containsExactly(
            tuple(
                ShippedTemplateFixtures.WORKBOOK_TICKER_CHF,
                new BigDecimal("25"),
                new BigDecimal("113.4"),
                new BigDecimal("2835"),
                "CHF"),
            tuple(
                ShippedTemplateFixtures.WORKBOOK_TICKER_USD,
                new BigDecimal("1200"),
                new BigDecimal("4.37"),
                new BigDecimal("5244"),
                "USD"),
            tuple(
                ShippedTemplateFixtures.WORKBOOK_TICKER_ETF,
                new BigDecimal("12.3456"),
                new BigDecimal("95.12"),
                new BigDecimal("1174.31"),
                "CHF"));
  }

  @Test
  void aCashWorkbookIsReadPerCurrencyWithoutItsTotal() throws Exception {
    ImportHoldingsStatement statement =
        reader.read(
            ShippedTemplateFixtures.postfinanceCashWorkbook(), sheetTemplate(CASH), MEMBER_DATE);

    assertThat(statement.lines())
        .extracting(Line::kind, Line::currency, Line::amount)
        .containsExactly(
            tuple(ImportHoldingsValues.KIND_CASH, "CHF", new BigDecimal("1204.35")),
            tuple(ImportHoldingsValues.KIND_CASH, "USD", new BigDecimal("310.2")));
  }

  @Test
  void aSpreadsheetNeedsTheMembersDateAndItsMappedColumns() throws Exception {
    byte[] cash = ShippedTemplateFixtures.postfinanceCashWorkbook();
    assertThatThrownBy(() -> reader.read(cash, sheetTemplate(CASH), null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("snapshotDate");
    // The positions template does not read the cash export: its header lacks Symbol and Anzahl.
    assertThatThrownBy(() -> reader.read(cash, sheetTemplate(POSITIONS), MEMBER_DATE))
        .isInstanceOfSatisfying(
            ImportFileRejectedException.class,
            e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_MISMATCH));
    assertThat(reader.readHeader(cash, sheetTemplate(CASH))).startsWith("Währung", "Kurs");
  }

  // --- the rule ----------------------------------------------------------------------------

  @Test
  void theShippedRulesAreValid() {
    assertThatCode(() -> ImportHoldingsReaderService.validate(pdfTemplate(SWISSQUOTE), null))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                ImportHoldingsReaderService.validate(
                    sheetTemplate(POSITIONS),
                    List.of("", "Symbol", "Anzahl", "Preis", "Totalwert", "Währung")))
        .doesNotThrowAnyException();
    assertThatCode(() -> ImportHoldingsReaderService.validate(sheetTemplate(CASH), null))
        .doesNotThrowAnyException();
  }

  @Test
  void aRuleThatCannotBeReadIsRefusedNamingItsField() {
    assertInvalid(
        pdfTemplate(rule(List.of("^(?P<quantity>[0-9]+) (?P<isin>[A-Z0-9]{12})$"))),
        "holdingsRule.positionPatterns[0]");
    assertInvalid(
        pdfTemplate(rule(List.of("^(?P<quantity>[0-9]+) (?P<value>[0-9.]+) (?P<x>.+)$"))),
        "holdingsRule.positionPatterns[0]");
    assertInvalid(pdfTemplate(rule(List.of())), "holdingsRule.positionPatterns");
    assertInvalid(
        pdfTemplate(
            new ImportHoldingsRule(
                "Marker", "^(?P<day>.+)$", null, null, SWISSQUOTE.positionPatterns(), null, null)),
        "holdingsRule.datePattern");
    assertInvalid(
        pdfTemplate(
            new ImportHoldingsRule(
                null,
                SWISSQUOTE.datePattern(),
                null,
                null,
                SWISSQUOTE.positionPatterns(),
                null,
                null)),
        "holdingsRule.documentMarker");
    assertInvalid(
        pdfTemplate(
            new ImportHoldingsRule(
                "Marker",
                SWISSQUOTE.datePattern(),
                null,
                null,
                SWISSQUOTE.positionPatterns(),
                null,
                CASH)),
        "holdingsRule.columns");
    // A spreadsheet is read by columns alone, and a position needs its value with its quantity.
    assertInvalid(
        sheetTemplate(
            new ImportHoldingsRule(
                null, SWISSQUOTE.datePattern(), null, null, List.of(), null, CASH)),
        "holdingsRule");
    assertInvalid(
        sheetTemplate(
            new ImportHoldingsColumns(null, "Symbol", null, "Anzahl", null, null, "Währung", null)),
        "holdingsRule.columns");
    assertInvalid(
        sheetTemplate(
            new ImportHoldingsColumns(null, null, null, null, null, null, null, "Kontosaldo")),
        "holdingsRule.columns.currency");
  }

  // --- helpers -----------------------------------------------------------------------------

  private void stubText(String... lines) {
    PdfImportReaderService stub = mock(PdfImportReaderService.class);
    when(stub.readTextLayer(any())).thenReturn(String.join("\n", lines));
    stubbed = new ImportHoldingsReaderService(stub, new SpreadsheetImportReaderService());
  }

  private ImportHoldingsStatement readStubbed(ImportTemplateDefinition template) {
    return stubbed.read(new byte[] {1}, template, null);
  }

  private static void assertInvalid(ImportTemplateDefinition template, String field) {
    assertThatThrownBy(() -> ImportHoldingsReaderService.validate(template, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_INVALID);
              assertThat(e.getBody().getProperties()).containsEntry("field", field);
            });
  }

  private static ImportHoldingsRule rule(List<String> positionPatterns) {
    return new ImportHoldingsRule(
        "Marker", SWISSQUOTE.datePattern(), null, null, positionPatterns, null, null);
  }

  static ImportTemplateDefinition pdfTemplate(ImportHoldingsRule rule) {
    return template("PDF_TEXT", rule);
  }

  static ImportTemplateDefinition sheetTemplate(ImportHoldingsColumns columns) {
    return template(
        "SPREADSHEET", new ImportHoldingsRule(null, null, null, null, null, null, columns));
  }

  static ImportTemplateDefinition sheetTemplate(ImportHoldingsRule rule) {
    return template("SPREADSHEET", rule);
  }

  private static ImportTemplateDefinition template(String format, ImportHoldingsRule rule) {
    return new ImportTemplateDefinition(
        "SNAPSHOT",
        ",",
        "UTF-8",
        ".",
        "'",
        "dd.MM.yyyy",
        0,
        0,
        0,
        "SINGLE_SIGNED_COLUMN",
        "FROM_ACCOUNT",
        null,
        new ImportColumnMapping(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null),
        Map.of(),
        "USER_SELECTED",
        format,
        null,
        Map.of(),
        null,
        null,
        null,
        rule);
  }
}
