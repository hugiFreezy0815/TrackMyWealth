package com.trackmywealth.backend.service;

import static com.trackmywealth.backend.service.SyntheticStatements.at;
import static com.trackmywealth.backend.service.SyntheticStatements.endingAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.error.ApiException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * #268, the statement layouts beyond one booking per line: columns found by the table's header
 * labels, the sign by column, currency sections, continuation lines, balance lines and the running
 * balance check, page furniture. On synthetic statements of the YUH-1 and SPK-6 layouts (see {@link
 * SyntheticStatements}); OCR is not involved.
 */
class PdfStatementLayoutTest {

  private static final String SPK_TITLE = "Invented Sparkasse Darlehenskonto";

  private final PdfImportReaderService reader =
      new PdfImportReaderService(mock(LocalOcrService.class));
  private final ImportFileParserService parser = new ImportFileParserService(reader);

  /**
   * Acceptance criterion 1: every booking parses with the sign of its column, the currency of its
   * section and its continuation lines in the description, and the balance check passes. Summary
   * text, balance lines, header lines, a remark at the margin and the page header and footer are no
   * bookings, and the table found again on a shifted second page still reads by its labels.
   */
  @Test
  void aYuhStatementParsesEveryBookingBySignColumnSectionAndContinuation() throws IOException {
    List<ParsedImportRow> rows =
        parser.parse(SyntheticStatements.yuhStatement(false), yuhTemplate(), null).rows();

    assertThat(rows).allSatisfy(row -> assertThat(row.isParsed()).as(row.toString()).isTrue());
    assertThat(rows)
        .extracting(ParsedImportRow::canonical)
        .extracting(
            CanonicalImportRow::bookingDate,
            CanonicalImportRow::amount,
            CanonicalImportRow::currency,
            CanonicalImportRow::description,
            CanonicalImportRow::externalId)
        .containsExactly(
            tuple(
                LocalDate.of(2031, 1, 3),
                new BigDecimal("1000.00"),
                "CHF",
                "Zahlung von Erika Beispiel CH00 0000 0000 0000 0000 0",
                "0000000001"),
            tuple(
                LocalDate.of(2031, 1, 5),
                new BigDecimal("-17.35"),
                "CHF",
                "Spareinlage",
                "0000000002"),
            tuple(
                LocalDate.of(2031, 1, 7), new BigDecimal("2.50"), "CHF", "Dividende", "0000000003"),
            tuple(
                LocalDate.of(2031, 1, 10),
                new BigDecimal("-40.00"),
                "EUR",
                "Zahlung an Max Muster",
                "0000000004"));
    assertThat(rows.get(0).canonical().valueDate()).isEqualTo(LocalDate.of(2031, 1, 3));
  }

  /** The raw data keeps every cell and the reconstructed line with its continuation lines. */
  @Test
  void theRawDataHoldsTheLineAndItsContinuationLines() throws IOException {
    ParsedImportRow first =
        parser.parse(SyntheticStatements.yuhStatement(false), yuhTemplate(), null).rows().get(0);

    assertThat(first.rawData())
        .containsEntry("GUTSCHRIFT", "1'000.00")
        .containsEntry("BELASTUNG", "")
        .containsEntry("SALDO", "1'263.40")
        .containsEntry("Waehrung", "CHF")
        .containsEntry(
            ImportFileParserService.RAW_LINE_KEY,
            "03.01.2031 Zahlung von 0000000001 1'000.00 03.01.2031 1'263.40\n"
                + "Erika Beispiel\n"
                + "CH00 0000 0000 0000 0000 0");
  }

  /**
   * Acceptance criterion 2: one amount in the wrong column is that row's balance mismatch; the row
   * after it starts from the balance the statement prints, so it and every other row still parse.
   */
  @Test
  void anAmountInTheWrongColumnIsABalanceMismatchAndTheOtherRowsParse() throws IOException {
    List<ParsedImportRow> rows =
        parser.parse(SyntheticStatements.yuhStatement(true), yuhTemplate(), null).rows();

    assertThat(rows).extracting(ParsedImportRow::isParsed).containsExactly(true, false, true, true);
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.BALANCE_MISMATCH);
    assertThat(rows.get(1).errorArgs())
        .containsEntry("value", "1246.05")
        .containsEntry("expected", "1280.75");
  }

  /** Without a balance column nothing is checked: the misread sign goes unnoticed. */
  @Test
  void withoutABalanceColumnNoRowIsChecked() throws IOException {
    ImportPdfLayout unchecked =
        PdfLayoutBuilder.from(SyntheticStatements.yuhLayout()).balanceColumn(null).build();

    List<ParsedImportRow> rows =
        parser.parse(SyntheticStatements.yuhStatement(true), yuhTemplate(unchecked), null).rows();

    assertThat(rows).allSatisfy(row -> assertThat(row.isParsed()).isTrue());
    assertThat(rows.get(1).canonical().amount()).isEqualTo(new BigDecimal("17.35"));
  }

  /**
   * SPK-6 layout without header labels: the continuation column is a row pattern column, so any
   * line below a booking continues it - except the page footer and header repeated on every page,
   * and a balance line.
   */
  @Test
  void aPageFooterAndHeaderAreNoContinuationLines() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "Kontostand am 01.01.2031"), endingAt(500, "-10.000,00")),
                    List.of(at(40, "02.01.2031Rate Januar"), endingAt(500, "250,00")),
                    List.of(at(60, "Erika Beispiel")),
                    List.of(at(40, "Seite 1 von 2"))),
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "02.02.2031Zinsen Januar"), endingAt(500, "-31,25")),
                    List.of(at(40, "Kontostand am 28.02.2031"), endingAt(500, "-9.781,25")),
                    List.of(at(40, "Seite 2 von 2")))));

    List<ParsedImportRow> rows = parser.parse(statement, spkTemplate(), null).rows();

    assertThat(rows)
        .extracting(row -> row.canonical().description())
        .containsExactly("Rate Januar Erika Beispiel", "Zinsen Januar");
  }

  /**
   * PR #281 review: a booking at the foot of a page continues on the next page, below that page's
   * header - the footer and header between are page furniture, which neither continues nor ends it.
   */
  @Test
  void aBookingContinuesAcrossAPageBreak() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "Kontostand am 01.01.2031"), endingAt(500, "-10.000,00")),
                    List.of(at(40, "02.01.2031Rate Januar"), endingAt(500, "250,00")),
                    List.of(at(40, "Seite 1 von 2"))),
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(60, "Erika Beispiel")),
                    List.of(at(40, "02.02.2031Zinsen Januar"), endingAt(500, "-31,25")),
                    List.of(at(40, "Seite 2 von 2")))));

    List<ParsedImportRow> rows = parser.parse(statement, spkTemplate(), null).rows();

    assertThat(rows)
        .extracting(row -> row.canonical().description())
        .containsExactly("Rate Januar Erika Beispiel", "Zinsen Januar");
  }

  /**
   * PR #281 review: a booking the balance line pattern takes for its own is lost without an error
   * row of its own - but the section's closing balance then differs from the balance the bookings
   * read lead to, and the booking above it is the error row that says so.
   */
  @Test
  void aClosingBalanceTheBookingsDoNotLeadToIsAnErrorRow() throws IOException {
    byte[] statement =
        oneSection(
            "100.00",
            SyntheticStatements.booking(
                0, "02.01.2031", "Lohn", "0000000001", null, "10.00", "110.00"),
            SyntheticStatements.booking(
                0, "03.01.2031", "Miete", "0000000002", "20.00", null, "90.00"),
            SyntheticStatements.booking(
                0, "04.01.2031", "Abschluss", "0000000003", null, "5.00", "95.00"),
            List.of(at(40, "Saldo per 31.01.2031"), endingAt(560, "95.00 CHF")));
    ImportPdfLayout layout =
        PdfLayoutBuilder.from(SyntheticStatements.yuhLayout())
            .balanceLinePattern("^Saldo per \\S+\\s+([-\\d'.]+)|Abschluss")
            .build();

    List<ParsedImportRow> rows = parser.parse(statement, yuhTemplate(layout), null).rows();

    assertThat(rows).extracting(ParsedImportRow::isParsed).containsExactly(true, false);
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.BALANCE_LINE_MISMATCH);
    assertThat(rows.get(1).errorArgs())
        .containsEntry("value", "95.00")
        .containsEntry("expected", "90.00");
  }

  /**
   * PR #281 review: a row that fails for another reason (here its date) has no amount to add, so
   * the next row starts from the balance it states, and still parses.
   */
  @Test
  void aRowFailingOtherwiseHandsOnTheBalanceItStates() throws IOException {
    byte[] statement =
        oneSection(
            "100.00",
            SyntheticStatements.booking(
                0, "02.01.2031", "Lohn", "0000000001", null, "10.00", "110.00"),
            SyntheticStatements.booking(
                0, "31.02.2031", "Miete", "0000000002", "20.00", null, "90.00"),
            SyntheticStatements.booking(
                0, "04.01.2031", "Bonus", "0000000003", null, "5.00", "95.00"),
            List.of(at(40, "Saldo per 31.01.2031"), endingAt(560, "95.00 CHF")));

    List<ParsedImportRow> rows = parser.parse(statement, yuhTemplate(), null).rows();

    assertThat(rows).extracting(ParsedImportRow::isParsed).containsExactly(true, false, true);
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.DATE_UNPARSEABLE);
  }

  /** PR #281 review: a balance line whose value is no amount checks nothing. */
  @Test
  void aBalanceLineThatIsNoAmountChecksNothing() throws IOException {
    byte[] statement =
        oneSection(
            "--",
            SyntheticStatements.booking(
                0, "02.01.2031", "Lohn", "0000000001", null, "10.00", "999.00"),
            SyntheticStatements.booking(
                0, "03.01.2031", "Miete", "0000000002", "9.00", null, "990.00"));

    List<ParsedImportRow> rows = parser.parse(statement, yuhTemplate(), null).rows();

    assertThat(rows).extracting(ParsedImportRow::isParsed).containsExactly(true, true);
  }

  /**
   * PR #281 review: a dry run reports a PDF's header fingerprint only when the statement holds the
   * header line of its labels - they come from the template, so without it they say nothing.
   */
  @Test
  void theFingerprintIsReportedOnlyForAStatementHoldingItsHeaderLine() throws IOException {
    assertThat(
            parser
                .parse(SyntheticStatements.yuhStatement(false), yuhTemplate(), null)
                .headerFingerprint())
        .isEqualTo(ImportFileParserService.fingerprint(SyntheticStatements.YUH_LABELS));

    byte[] headless =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, SyntheticStatements.YUH_MARKER)),
                    List.of(at(40, "Kontoauszug in CHF")),
                    SyntheticStatements.booking(
                        0, "02.01.2031", "Lohn", "0000000001", null, "10.00", "110.00"))));
    assertThat(parser.parse(headless, yuhTemplate(), null).headerFingerprint()).isNull();
  }

  /** Detection's tests on the text alone: the marker with the header line, or a booking line. */
  @Test
  void aStatementWithItsHeaderLineIsAnExactMatch() throws IOException {
    String text = reader.readTextLayer(SyntheticStatements.yuhStatement(false));
    ImportPdfLayout yuh = SyntheticStatements.yuhLayout();

    assertThat(PdfImportReaderService.hasHeaderLine(text, yuh)).isTrue();
    assertThat(PdfImportReaderService.isLayoutOf(text, yuh)).isTrue();
    // Without labels a layout has no header line to find; another header is not this one.
    ImportPdfLayout unlabelled =
        PdfLayoutBuilder.of(yuh.columns(), yuh.rowPattern(), yuh.documentMarker(), "^\\d").build();
    assertThat(PdfImportReaderService.hasHeaderLine(text, unlabelled)).isFalse();
    assertThat(PdfImportReaderService.hasHeaderLine(text, withLabels(yuh, List.of("BETRAG"))))
        .isFalse();
  }

  /**
   * The layout contract of the #267 review: a layout saved with only the four fields of #267 reads
   * as one without any of the optional ones, unchanged.
   */
  @Test
  void aLayoutSavedBeforeTheOptionalFieldsReadsUnchanged() {
    ImportPdfLayout stored =
        JsonMapper.builder()
            .build()
            .readValue(
                "{\"columns\":[\"Datum\",\"Betrag\"],\"rowPattern\":\"(\\\\S+) (\\\\S+)\","
                    + "\"documentMarker\":\"Invented\",\"recordStartPattern\":\"^\\\\d\"}",
                ImportPdfLayout.class);

    assertThat(stored)
        .isEqualTo(
            new ImportPdfLayout(List.of("Datum", "Betrag"), "(\\S+) (\\S+)", "Invented", "^\\d"));
    assertThat(stored.cellNames()).containsExactly("Datum", "Betrag");
  }

  @Test
  void theOptionalFieldsAreValidated() {
    ImportPdfLayout yuh = SyntheticStatements.yuhLayout();
    // Header labels need positions on the page, which OCR does not give.
    assertInvalid(yuhTemplate(yuh, "PDF_OCR"), "pdfLayout.headerLabels");
    // A label must not repeat a column's name.
    assertInvalid(
        yuhTemplate(withLabels(yuh, List.of("DATUM", "zeile"))), "pdfLayout.headerLabels");
    assertInvalid(yuhTemplate(withLabels(yuh, List.of("DATUM", " "))), "pdfLayout.headerLabels");
    assertInvalid(
        yuhTemplate(withOptional(yuh, "Unknown", yuh.sectionPattern(), "SALDO")),
        "pdfLayout.continuationColumn");
    assertInvalid(
        yuhTemplate(withOptional(yuh, "INFORMATION", yuh.sectionPattern(), "Unknown")),
        "pdfLayout.balanceColumn");
    // The section column holds the section pattern's first group, so it needs one.
    assertInvalid(
        yuhTemplate(withOptional(yuh, "INFORMATION", "^Kontoauszug in", "SALDO")),
        "pdfLayout.sectionColumn");
    assertInvalid(
        yuhTemplate(withOptional(yuh, "INFORMATION", "(", "SALDO")), "pdfLayout.sectionPattern");
  }

  // --- helpers -------------------------------------------------------------------------------

  private static ImportTemplateDefinition yuhTemplate() {
    return yuhTemplate(SyntheticStatements.yuhLayout());
  }

  private static ImportTemplateDefinition yuhTemplate(ImportPdfLayout layout) {
    return yuhTemplate(layout, "PDF_TEXT");
  }

  private static ImportTemplateDefinition yuhTemplate(ImportPdfLayout layout, String format) {
    ImportColumnMapping mapping = SyntheticStatements.yuhMapping();
    return new ImportFileParserServiceTest.Template()
        .pdf(format, layout)
        .mapping(mapping)
        .amountRepresentation("SEPARATE_DEBIT_CREDIT")
        .currencyMode("PER_ROW")
        .fixedCurrency(null)
        .dateFormat("dd.MM.yyyy")
        .decimal(".")
        .thousands("'")
        .build();
  }

  // The SPK-6 layout: the date glued to the text, signed amounts, no header labels.
  private static ImportTemplateDefinition spkTemplate() {
    ImportPdfLayout layout =
        PdfLayoutBuilder.of(
                List.of("Datum", "Text", "Betrag"),
                "(\\d{2}\\.\\d{2}\\.\\d{4})(.+?)\\s+(-?[\\d.]+,\\d{2})",
                SPK_TITLE,
                "^\\d{2}\\.\\d{2}\\.\\d{4}")
            .continuationColumn("Text")
            .balanceLinePattern("^Kontostand am")
            .build();
    return new ImportFileParserServiceTest.Template()
        .pdf("PDF_TEXT", layout)
        .mapping(ImportFileParserServiceTest.mapping("Datum", "Betrag").description("Text").build())
        .dateFormat("dd.MM.yyyy")
        .decimal(",")
        .thousands(".")
        .build();
  }

  // A one-page statement in the YUH-1 layout: a CHF section opening with the given balance, its
  // header line, then the given lines.
  @SafeVarargs
  private static byte[] oneSection(String opening, List<SyntheticStatements.Cell>... lines)
      throws IOException {
    List<List<SyntheticStatements.Cell>> page = new ArrayList<>();
    page.add(List.of(at(40, SyntheticStatements.YUH_MARKER)));
    page.add(List.of(at(40, "Kontoauszug in CHF")));
    page.add(List.of(at(40, "Saldo per 01.01.2031"), endingAt(560, opening + " CHF")));
    page.add(SyntheticStatements.header(0));
    page.addAll(List.of(lines));
    return SyntheticStatements.statement(List.of(page));
  }

  private static ImportPdfLayout withLabels(ImportPdfLayout layout, List<String> labels) {
    return PdfLayoutBuilder.from(layout)
        .headerLabels(labels)
        .continuationColumn(null)
        .sectionPattern(null)
        .sectionColumn(null)
        .balanceLinePattern(null)
        .balanceColumn(null)
        .build();
  }

  private static ImportPdfLayout withOptional(
      ImportPdfLayout layout, String continuation, String section, String balance) {
    return PdfLayoutBuilder.from(layout)
        .continuationColumn(continuation)
        .sectionPattern(section)
        .balanceColumn(balance)
        .build();
  }

  private void assertInvalid(ImportTemplateDefinition template, String field) {
    assertThatThrownBy(() -> parser.validateTemplate(template, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getBody().getProperties()).containsEntry("field", field));
  }
}
