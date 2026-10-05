package com.trackmywealth.backend.service;

import static com.trackmywealth.backend.service.SyntheticStatements.at;
import static com.trackmywealth.backend.service.SyntheticStatements.atFoot;
import static com.trackmywealth.backend.service.SyntheticStatements.endingAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportPdfBookingLine;
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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * #268, the statement layouts beyond one booking per line: columns found by the table's header
 * labels, the sign by column, currency sections, continuation lines, balance lines and the running
 * balance check, page furniture. On synthetic statements of the YUH-1 and SPK-6 layouts (see {@link
 * SyntheticStatements}); OCR is not involved.
 */
class PdfStatementLayoutTest {

  private static final String SPK_TITLE = "Invented Sparkasse Darlehenskonto";
  private static final String GENERIC_TITLE = "Invented statement";

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
                    List.of(atFoot(40, "Seite 1 von 2"))),
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "02.02.2031Zinsen Januar"), endingAt(500, "-31,25")),
                    List.of(at(40, "Kontostand am 28.02.2031"), endingAt(500, "-9.781,25")),
                    List.of(atFoot(40, "Seite 2 von 2")))));

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
                    List.of(atFoot(40, "Seite 1 von 2"))),
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(60, "Erika Beispiel")),
                    List.of(at(40, "02.02.2031Zinsen Januar"), endingAt(500, "-31,25")),
                    List.of(atFoot(40, "Seite 2 von 2")))));

    List<ParsedImportRow> rows = parser.parse(statement, spkTemplate(), null).rows();

    assertThat(rows)
        .extracting(row -> row.canonical().description())
        .containsExactly("Rate Januar Erika Beispiel", "Zinsen Januar");
  }

  /**
   * PR #281 review: a booking at the foot of a page continues below the table header the next page
   * repeats. Its own cells keep its page's columns; its continuation lines are placed by the next
   * page's, which sit elsewhere.
   */
  @Test
  void aBookingContinuesBelowTheTableHeaderOfTheNextPage() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, GENERIC_TITLE)),
                    genericHeader(0),
                    List.of(at(40, "01.01.2031"), at(150, "Payment"), at(400, "10.00")),
                    List.of(atFoot(40, "Page 1"))),
                List.of(
                    List.of(at(40, GENERIC_TITLE)),
                    genericHeader(200),
                    List.of(at(350, "Invented recipient")),
                    List.of(at(240, "02.01.2031"), at(350, "Other"), at(600, "20.00")),
                    List.of(atFoot(40, "Page 2")))));

    List<ImportPdfBookingLine> bookings = reader.readBookingLines(statement, genericTemplate());

    assertThat(bookings)
        .extracting(ImportPdfBookingLine::cells)
        .containsExactly(
            List.of(
                "01.01.2031 Payment 10.00", "01.01.2031", "Payment Invented recipient", "10.00"),
            List.of("02.01.2031 Other 20.00", "02.01.2031", "Other", "20.00"));
    List<ParsedImportRow> rows = parser.parse(statement, genericTemplate(), null).rows();
    assertThat(rows).allSatisfy(row -> assertThat(row.isParsed()).isTrue());
    assertThat(rows.get(0).rawData())
        .containsEntry(
            ImportFileParserService.RAW_LINE_KEY, "01.01.2031 Payment 10.00\nInvented recipient");
  }

  /**
   * PR #281 review: only the table header at the top of the next page carries a booking over. A
   * second table on that page ends it: what is under that table's header is no part of it.
   */
  @Test
  void aSecondTableHeaderOnTheNextPageEndsTheBooking() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, GENERIC_TITLE)),
                    genericHeader(0),
                    List.of(at(40, "01.01.2031"), at(150, "Payment"), at(400, "10.00")),
                    List.of(atFoot(40, "Page 1"))),
                List.of(
                    List.of(at(40, GENERIC_TITLE)),
                    genericHeader(0),
                    List.of(at(150, "Invented recipient")),
                    genericHeader(0),
                    List.of(at(150, "Note of the second table")),
                    List.of(at(40, "02.01.2031"), at(150, "Other"), at(400, "20.00")),
                    List.of(atFoot(40, "Page 2")))));

    List<ImportPdfBookingLine> bookings = reader.readBookingLines(statement, genericTemplate());

    assertThat(bookings.get(0).continuation()).containsExactly("Invented recipient");
    assertThat(bookings.get(1).continuation()).isEmpty();
  }

  /**
   * PR #281 review: a counterparty that ends a booking at the foot of every page repeats there as a
   * footer would, but sits at another height on each page, and stays in its booking.
   */
  @Test
  void aCounterpartyEndingABookingOnEveryPageStaysInIt() throws IOException {
    List<SyntheticStatements.Cell> landlord = List.of(at(60, "Vermieter GmbH"));
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "Kontostand am 01.01.2031"), endingAt(500, "-10.000,00")),
                    List.of(at(40, "02.01.2031Rate Januar"), endingAt(500, "250,00")),
                    List.of(at(40, "03.01.2031Miete Januar"), endingAt(500, "-850,00")),
                    landlord,
                    List.of(atFoot(40, "Seite 1 von 3"))),
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "02.02.2031Rate Februar"), endingAt(500, "250,00")),
                    List.of(at(40, "03.02.2031Zinsen"), endingAt(500, "-31,25")),
                    List.of(at(40, "04.02.2031Gebuehr"), endingAt(500, "-5,00")),
                    List.of(at(40, "05.02.2031Miete Februar"), endingAt(500, "-850,00")),
                    landlord,
                    List.of(atFoot(40, "Seite 2 von 3"))),
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "03.03.2031Miete Maerz"), endingAt(500, "-850,00")),
                    landlord,
                    List.of(atFoot(40, "Seite 3 von 3")))));

    List<ParsedImportRow> rows = parser.parse(statement, spkTemplate(), null).rows();

    assertThat(rows)
        .extracting(row -> row.canonical().description())
        .containsExactly(
            "Rate Januar",
            "Miete Januar Vermieter GmbH",
            "Rate Februar",
            "Zinsen",
            "Gebuehr",
            "Miete Februar Vermieter GmbH",
            "Miete Maerz Vermieter GmbH");
  }

  /**
   * PR #281 review: a page number is set aside wherever a footer line holds it, in the forms banks
   * print, so the footer is never appended to the page's last booking.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Kontoauszug 1/2031 - Blatt %d von 2", "%d/2", "- %d -", "S. %d"})
  void aFooterWithItsPageNumberIsNoContinuationLine(String footer) throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "02.01.2031Rate Januar"), endingAt(500, "250,00")),
                    List.of(at(60, "Erika Beispiel")),
                    List.of(atFoot(40, String.format(footer, 1)))),
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "02.02.2031Rate Februar"), endingAt(500, "250,00")),
                    List.of(atFoot(40, String.format(footer, 2))))));

    List<ParsedImportRow> rows = parser.parse(statement, spkTemplate(), null).rows();

    assertThat(rows)
        .extracting(row -> row.canonical().description())
        .containsExactly("Rate Januar Erika Beispiel", "Rate Februar");
  }

  /**
   * PR #281 review: references ending a booking at the foot of each page differ only in their
   * digits, short or long, and are no footer: only page numbers set their digits aside.
   */
  @ParameterizedTest
  @CsvSource({"Reference 111111, Reference 222222", "Reference 111, Reference 222"})
  void referencesAtTheFootOfEachPageAreNoFurniture(String first, String second) throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, GENERIC_TITLE)),
                    List.of(at(40, "01.01.2031 Payment 10.00")),
                    List.of(at(60, first)),
                    List.of(atFoot(40, "Page 1"))),
                List.of(
                    List.of(at(40, GENERIC_TITLE)),
                    List.of(at(40, "02.01.2031 Payment 20.00")),
                    List.of(at(60, second)),
                    List.of(atFoot(40, "Page 2")))));
    ImportPdfLayout layout =
        PdfLayoutBuilder.of(List.of("Line"), "(.*)", GENERIC_TITLE, "^\\d{2}\\.")
            .continuationColumn("Line")
            .build();
    ImportTemplateDefinition template =
        new ImportFileParserServiceTest.Template().pdf("PDF_TEXT", layout).build();

    assertThat(reader.readBookingLines(statement, template))
        .extracting(ImportPdfBookingLine::continuation)
        .containsExactly(List.of(first), List.of(second));
  }

  /**
   * PR #281 review: a booking the balance line pattern takes for its own is lost without an error
   * row of its own - but the section's closing balance then differs from the balance the bookings
   * read lead to. The balance line is the error row that says so; the bookings above it were read
   * correctly and stay parsed, so they still import.
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

    assertThat(rows).extracting(ParsedImportRow::isParsed).containsExactly(true, true, false);
    ParsedImportRow balanceLine = rows.get(2);
    assertThat(balanceLine.rowNumber()).isEqualTo(3);
    assertThat(balanceLine.errorCode()).isEqualTo(ImportRowErrorValues.BALANCE_LINE_MISMATCH);
    assertThat(balanceLine.errorArgs())
        .containsEntry("value", "95.00")
        .containsEntry("expected", "90.00");
    assertThat(balanceLine.rawData())
        .containsExactly(
            Map.entry(ImportFileParserService.RAW_LINE_KEY, "Saldo per 31.01.2031 95.00 CHF"));
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
   * PR #281 review: a statement holding the marker and the header line but no booking line is no
   * candidate at all, let alone an exact match.
   */
  @Test
  void aHeaderLineWithoutABookingLineIsNoMatch() throws IOException {
    String text =
        reader.readTextLayer(
            SyntheticStatements.statement(
                List.of(
                    List.of(
                        List.of(at(40, SyntheticStatements.YUH_MARKER)),
                        List.of(at(40, "Kontoauszug in CHF")),
                        SyntheticStatements.header(0)))));
    ImportPdfLayout yuh = SyntheticStatements.yuhLayout();

    assertThat(PdfImportReaderService.isLayoutOf(text, yuh)).isFalse();
    assertThat(PdfImportReaderService.hasHeaderLine(text, yuh)).isFalse();
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
  // A table of three labels, from x = 40 on, shifted by shift.
  private static List<SyntheticStatements.Cell> genericHeader(float shift) {
    return List.of(
        at(40 + shift, "Date"), at(150 + shift, "Description"), at(400 + shift, "Amount"));
  }

  // Every line one cell, the words under each label, continuation lines under Description.
  private static ImportTemplateDefinition genericTemplate() {
    ImportPdfLayout layout =
        PdfLayoutBuilder.of(List.of("Line"), "(.*)", GENERIC_TITLE, "^\\d{2}\\.")
            .headerLabels(List.of("Date", "Description", "Amount"))
            .continuationColumn("Description")
            .build();
    return new ImportFileParserServiceTest.Template()
        .pdf("PDF_TEXT", layout)
        .mapping(
            ImportFileParserServiceTest.mapping("Date", "Amount")
                .description("Description")
                .build())
        .dateFormat("dd.MM.yyyy")
        .build();
  }

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
