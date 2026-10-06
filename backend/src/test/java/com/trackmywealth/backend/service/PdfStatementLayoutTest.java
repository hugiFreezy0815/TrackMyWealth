package com.trackmywealth.backend.service;

import static com.trackmywealth.backend.service.SyntheticStatements.at;
import static com.trackmywealth.backend.service.SyntheticStatements.atFoot;
import static com.trackmywealth.backend.service.SyntheticStatements.endingAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportPdfBookingLine;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
  private final ImportFileParserService parser =
      new ImportFileParserService(reader, new PdfBalanceCheckService());

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

  /**
   * Without a balance column nothing is checked: the misread sign goes unnoticed. Nor can anything
   * tell the dated opening entry, which the balance line pattern and the record-start pattern both
   * find, from a booking the balance pattern took: it is reported, never silently skipped (PR #281
   * review).
   */
  @Test
  void withoutABalanceColumnNoRowIsChecked() throws IOException {
    ImportPdfLayout unchecked =
        SyntheticStatements.yuhLayout().toBuilder().withBalanceColumn(null).build();

    List<ParsedImportRow> rows =
        parser.parse(SyntheticStatements.yuhStatement(true), yuhTemplate(unchecked), null).rows();

    assertThat(rows.get(0).errorCode()).isEqualTo(ImportRowErrorValues.LINE_AMBIGUOUS);
    assertThat(rows.get(0).errorArgs().get("value")).contains("Anfangsbestand");
    List<ParsedImportRow> bookings = rows.subList(1, rows.size());
    assertThat(bookings).allSatisfy(row -> assertThat(row.isParsed()).isTrue());
    assertThat(bookings.get(1).canonical().amount()).isEqualTo(new BigDecimal("17.35"));
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
        ImportPdfLayout.builder(List.of("Line"), "(.*)", GENERIC_TITLE, "^\\d{2}\\.")
            .withContinuationColumn("Line")
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
        SyntheticStatements.yuhLayout().toBuilder()
            .withBalanceLinePattern("^Saldo per \\S+\\s+([-\\d'.]+)|Abschluss")
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
   * PR #281 review: without a balance column to check it, a balance line pattern that also finds a
   * booking line would drop that booking without a trace. The line is an error row of its own
   * instead, and the bookings around it still parse.
   */
  @Test
  void aBookingLineTheBalancePatternFindsTooIsAnErrorRow() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "02.01.2031Rate Januar"), endingAt(500, "250,00")),
                    List.of(at(40, "03.01.2031Saldoausgleich"), endingAt(500, "99,00")),
                    List.of(at(40, "04.01.2031Zinsen"), endingAt(500, "-1,00")))));
    ImportTemplateDefinition template =
        spkTemplate(spkTemplate().pdfLayout().toBuilder().withBalanceLinePattern("Saldo").build());

    List<ParsedImportRow> rows = parser.parse(statement, template, null).rows();

    assertThat(rows).extracting(ParsedImportRow::isParsed).containsExactly(true, false, true);
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.LINE_AMBIGUOUS);
    assertThat(rows.get(1).errorArgs())
        .containsEntry("value", "03.01.2031Saldoausgleich 99,00")
        .containsEntry("pattern", "balanceLinePattern");
  }

  /**
   * PR #281 review: a section pattern that also finds a booking line is reported the same way - a
   * section's start resets the running balance, so no balance check would catch it. The section
   * still starts there, so the rows after it keep the meaning the layout gives them.
   */
  @Test
  void aBookingLineTheSectionPatternFindsTooIsAnErrorRow() throws IOException {
    byte[] statement =
        oneSection(
            "100.00",
            SyntheticStatements.booking(
                0, "02.01.2031", "Lohn", "0000000001", null, "10.00", "110.00"),
            SyntheticStatements.booking(
                0, "03.01.2031", "Kontoauszug in EUR", "0000000002", "20.00", null, "90.00"),
            SyntheticStatements.booking(
                0, "04.01.2031", "Bonus", "0000000003", null, "5.00", "95.00"));
    ImportPdfLayout layout =
        SyntheticStatements.yuhLayout().toBuilder()
            .withSectionPattern("Kontoauszug in ([A-Z]{3})")
            .build();

    List<ParsedImportRow> rows = parser.parse(statement, yuhTemplate(layout), null).rows();

    assertThat(rows).extracting(ParsedImportRow::isParsed).containsExactly(true, false, true);
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.LINE_AMBIGUOUS);
    assertThat(rows.get(1).errorArgs()).containsEntry("pattern", "sectionPattern");
    assertThat(rows.get(2).canonical().currency()).isEqualTo("EUR");
  }

  /**
   * PR #281 architecture review: a statement that repeats its section's marker at the top of the
   * next page continues the section there. The booking at the foot of the first page keeps its
   * continuation line from the next, and the next page's first booking is checked against the
   * running balance - a misprinted one is a balance mismatch, not an unchecked section start.
   *
   * <p>The misprinted statement is also, word for word, a second CHF account opening at the top of
   * the page without an opening balance line: nothing in the text tells the two apart, so such an
   * account's first booking is a balance mismatch too - an error row, never a booking imported
   * unchecked (#268, documented on {@code ImportPdfLayout}).
   */
  @Test
  void aSectionMarkerRepeatedOnTheNextPageContinuesTheSection() throws IOException {
    List<ParsedImportRow> rows =
        parser.parse(chfOnTwoPages(null, "95.00"), yuhTemplate(), null).rows();

    assertThat(rows).allSatisfy(row -> assertThat(row.isParsed()).as(row.toString()).isTrue());
    assertThat(rows)
        .extracting(row -> row.canonical().description())
        .containsExactly("Lohn", "Miete Vermieter AG", "Bonus");

    List<ParsedImportRow> misprinted =
        parser.parse(chfOnTwoPages(null, "96.00"), yuhTemplate(), null).rows();

    assertThat(misprinted).extracting(ParsedImportRow::isParsed).containsExactly(true, true, false);
    assertThat(misprinted.get(2).errorCode()).isEqualTo(ImportRowErrorValues.BALANCE_MISMATCH);
    assertThat(misprinted.get(2).errorArgs())
        .containsEntry("value", "96.00")
        .containsEntry("expected", "95.00");
  }

  /**
   * PR #281 architecture review: a repeated marker cannot tell a section going on from a new one of
   * the same currency (a second CHF account) starting at the top of the page. A balance line after
   * it therefore only opens the balance the next booking starts from, never one that the bookings
   * before must lead to: a correct statement gets no error row.
   */
  @Test
  void aNewSectionOfTheSameValueAtThePageTopOpensWithItsOwnBalance() throws IOException {
    List<ParsedImportRow> rows =
        parser.parse(chfOnTwoPages("500.00", "505.00"), yuhTemplate(), null).rows();

    assertThat(rows).allSatisfy(row -> assertThat(row.isParsed()).as(row.toString()).isTrue());
    assertThat(rows).hasSize(3);
  }

  /**
   * PR #281 architecture review: a marker of the same value below a booking on its page - here on
   * the page after the section's own marker - is no repetition of a page's title, but a new
   * section, whose first booking starts no running balance of the section before.
   */
  @Test
  void aSectionMarkerBelowABookingOnItsPageStartsANewSection() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, SyntheticStatements.YUH_MARKER)),
                    List.of(at(40, "Kontoauszug in CHF")),
                    List.of(at(40, "Saldo per 01.01.2031"), endingAt(560, "100.00 CHF")),
                    SyntheticStatements.header(0),
                    SyntheticStatements.booking(
                        0, "02.01.2031", "Lohn", "0000000001", null, "10.00", "110.00")),
                List.of(
                    List.of(at(40, SyntheticStatements.YUH_MARKER)),
                    SyntheticStatements.header(0),
                    SyntheticStatements.booking(
                        0, "03.01.2031", "Miete", "0000000002", "20.00", null, "90.00"),
                    List.of(at(40, "Kontoauszug in CHF")),
                    SyntheticStatements.header(0),
                    SyntheticStatements.booking(
                        0, "04.01.2031", "Zins", "0000000003", null, "1.00", "51.00"))));

    List<ParsedImportRow> rows = parser.parse(statement, yuhTemplate(), null).rows();

    assertThat(rows).allSatisfy(row -> assertThat(row.isParsed()).as(row.toString()).isTrue());
    assertThat(rows).hasSize(3);
  }

  /**
   * PR #281 architecture review: one table header above every section sets the columns of all of
   * them; the text before the first section is skipped, but not its header line.
   */
  @Test
  void aTableHeaderAboveTheFirstSectionSetsItsColumns() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, SyntheticStatements.YUH_MARKER)),
                    SyntheticStatements.header(0),
                    List.of(at(40, "Kontoauszug in CHF")),
                    List.of(at(40, "Saldo per 01.01.2031"), endingAt(560, "100.00 CHF")),
                    SyntheticStatements.booking(
                        0, "02.01.2031", "Lohn", "0000000001", null, "10.00", "110.00"),
                    List.of(at(40, "Kontoauszug in EUR")),
                    List.of(at(40, "Saldo per 01.01.2031"), endingAt(560, "50.00 EUR")),
                    SyntheticStatements.booking(
                        0, "03.01.2031", "Zahlung", "0000000002", "20.00", null, "30.00"))));

    List<ParsedImportRow> rows = parser.parse(statement, yuhTemplate(), null).rows();

    assertThat(rows).allSatisfy(row -> assertThat(row.isParsed()).as(row.toString()).isTrue());
    assertThat(rows)
        .extracting(row -> row.canonical().amount(), row -> row.canonical().currency())
        .containsExactly(
            tuple(new BigDecimal("10.00"), "CHF"), tuple(new BigDecimal("-20.00"), "EUR"));
  }

  /**
   * PR #281 architecture review: a carry-forward line states another balance on each page, so it is
   * no page furniture, and without header labels it would continue the page's last booking. Named
   * in the balance line pattern, it is a balance line and continues no booking.
   */
  @Test
  void carryForwardLinesInTheBalancePatternContinueNoBooking() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "Kontostand am 01.01.2031"), endingAt(500, "-10.000,00")),
                    List.of(at(40, "02.01.2031Rate Januar"), endingAt(500, "250,00")),
                    List.of(at(60, "Erika Beispiel")),
                    List.of(at(40, "Uebertrag"), endingAt(500, "-9.750,00")),
                    List.of(atFoot(40, "Seite 1 von 2"))),
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "Uebertrag"), endingAt(500, "-9.750,00")),
                    List.of(at(40, "02.02.2031Zinsen Januar"), endingAt(500, "-31,25")),
                    List.of(atFoot(40, "Seite 2 von 2")))));

    assertThat(parser.parse(statement, spkTemplate(), null).rows().get(0).canonical().description())
        .isEqualTo("Rate Januar Erika Beispiel Uebertrag -9.750,00 Uebertrag -9.750,00");

    ImportTemplateDefinition template =
        spkTemplate(
            spkTemplate().pdfLayout().toBuilder()
                .withBalanceLinePattern("^(?:Kontostand am|Uebertrag)")
                .build());
    List<ParsedImportRow> rows = parser.parse(statement, template, null).rows();

    assertThat(rows)
        .extracting(row -> row.canonical().description())
        .containsExactly("Rate Januar Erika Beispiel", "Zinsen Januar");
  }

  /**
   * PR #281 review: without header labels any line below a booking continues it, so the closing
   * text after a statement's last booking would end up in its description. The continuation end
   * pattern ends it there.
   */
  @Test
  void theContinuationEndPatternKeepsClosingTextOutOfTheLastBooking() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, SPK_TITLE)),
                    List.of(at(40, "02.01.2031Rate Januar"), endingAt(500, "250,00")),
                    List.of(at(60, "Erika Beispiel")),
                    List.of(at(40, "Bitte pruefen Sie diesen Auszug.")),
                    List.of(at(60, "Einwendungen innerhalb von sechs Wochen.")))));
    ImportTemplateDefinition template =
        spkTemplate(
            spkTemplate().pdfLayout().toBuilder()
                .withContinuationEndPattern("^Bitte pruefen")
                .build());

    List<ParsedImportRow> rows = parser.parse(statement, template, null).rows();

    assertThat(rows)
        .extracting(row -> row.canonical().description())
        .containsExactly("Rate Januar Erika Beispiel");
  }

  /**
   * PR #281 review: a booking with more continuation lines than one may have is an error row, never
   * one with the rest of the statement glued to its description; the bookings before it parse.
   */
  @Test
  void aBookingWithTooManyContinuationLinesIsAnErrorRow() throws IOException {
    List<List<SyntheticStatements.Cell>> page = new ArrayList<>();
    page.add(List.of(at(40, SPK_TITLE)));
    page.add(List.of(at(40, "01.01.2031Rate Dezember"), endingAt(500, "250,00")));
    page.add(List.of(at(40, "02.01.2031Rate Januar"), endingAt(500, "250,00")));
    for (int i = 0; i <= ImportPdfLayout.MAX_CONTINUATION_LINES; i++) {
      page.add(List.of(at(40, "Invented condition " + i)));
    }

    List<ParsedImportRow> rows =
        parser.parse(SyntheticStatements.statement(List.of(page)), spkTemplate(), null).rows();

    assertThat(rows).extracting(ParsedImportRow::isParsed).containsExactly(true, false);
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.CONTINUATION_TOO_LONG);
    assertThat(rows.get(1).errorArgs())
        .containsEntry("max", String.valueOf(ImportPdfLayout.MAX_CONTINUATION_LINES));
  }

  /** As many continuation lines as a booking may have still continue it. */
  @Test
  void aBookingWithTheMostContinuationLinesParses() throws IOException {
    List<List<SyntheticStatements.Cell>> page = new ArrayList<>();
    page.add(List.of(at(40, SPK_TITLE)));
    page.add(List.of(at(40, "02.01.2031Rate Januar"), endingAt(500, "250,00")));
    for (int i = 0; i < ImportPdfLayout.MAX_CONTINUATION_LINES; i++) {
      page.add(List.of(at(40, "Line" + i)));
    }

    List<ParsedImportRow> rows =
        parser.parse(SyntheticStatements.statement(List.of(page)), spkTemplate(), null).rows();

    assertThat(rows).extracting(ParsedImportRow::isParsed).containsExactly(true);
    assertThat(rows.get(0).canonical().description()).endsWith(" Line19");
  }

  /**
   * PR #281 review: the error rows of balance lines count against the file's row limit, so a
   * statement cannot hold more rows than a file may by its balance lines.
   */
  @Test
  void balanceLineErrorRowsCountAgainstTheRowLimit() throws IOException {
    ImportPdfBookingLine read =
        reader.readBookingLines(SyntheticStatements.yuhStatement(false), yuhTemplate()).get(0);
    ImportPdfBookingLine withBalanceLines =
        ImportPdfBookingLine.builder(read.line(), read.cells(), read.status())
            .withSectionStart(read.sectionStart())
            .withStatedBalance(read.statedBalance())
            .withBalancesAfter(
                Collections.nCopies(
                    ImportFileParserService.MAX_DATA_ROWS,
                    new ImportPdfBookingLine.BalanceLine("Saldo per 31.01.2031 1.00", "1.00")))
            .withHeaded(true)
            .build();
    PdfImportReaderService stub = mock(PdfImportReaderService.class);
    when(stub.readBookingLines(any(), any())).thenReturn(List.of(withBalanceLines));

    assertThatThrownBy(
            () ->
                new ImportFileParserService(stub, new PdfBalanceCheckService())
                    .parse(new byte[0], yuhTemplate(), null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_FILE_TOO_MANY_ROWS));
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
        ImportPdfLayout.builder(yuh.columns(), yuh.rowPattern(), yuh.documentMarker(), "^\\d")
            .build();
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

    // Detection asks isLayoutOf first: the header line alone makes no candidate.
    assertThat(PdfImportReaderService.isLayoutOf(text, yuh)).isFalse();
    assertThat(PdfImportReaderService.hasHeaderLine(text, yuh)).isTrue();
  }

  /**
   * PR #281 review: a statement that puts a negative amount in parentheses puts a negative balance
   * (a loan account's, all along) in them too. Its bookings and its balance lines are checked, not
   * error rows for a balance that is no amount.
   */
  @Test
  void aNegativeBalanceInParenthesesIsChecked() throws IOException {
    ImportPdfLayout layout =
        ImportPdfLayout.builder(
                List.of("Datum", "Text", "Betrag", "Saldo"),
                "(\\d{2}\\.\\d{2}\\.\\d{4}) (.+?) (\\S+) (\\S+)",
                GENERIC_TITLE,
                "^\\d{2}\\.\\d{2}\\.\\d{4}")
            .withBalanceLinePattern("^Closing balance (\\S+)")
            .withBalanceColumn("Saldo")
            .build();
    ImportTemplateDefinition template =
        new ImportFileParserServiceTest.Template()
            .pdf("PDF_TEXT", layout)
            .mapping(
                ImportFileParserServiceTest.mapping("Datum", "Betrag").description("Text").build())
            .dateFormat("dd.MM.yyyy")
            .amountRepresentation("NEGATIVE_IN_PARENTHESES")
            .build();

    List<ParsedImportRow> rows =
        parser.parse(loanStatement("(1050.00)", "(1050.00)"), template, null).rows();

    assertThat(rows).allSatisfy(row -> assertThat(row.isParsed()).as(row.toString()).isTrue());
    assertThat(rows)
        .extracting(row -> row.canonical().amount())
        .containsExactly(new BigDecimal("-100.00"), new BigDecimal("50.00"));

    List<ParsedImportRow> misprinted =
        parser.parse(loanStatement("(1060.00)", "(1040.00)"), template, null).rows();

    assertThat(misprinted)
        .extracting(ParsedImportRow::errorCode)
        .containsExactly(
            null,
            ImportRowErrorValues.BALANCE_MISMATCH,
            ImportRowErrorValues.BALANCE_LINE_MISMATCH);
    assertThat(misprinted.get(1).errorArgs())
        .containsEntry("value", "-1060.00")
        .containsEntry("expected", "-1050.00");
    assertThat(misprinted.get(2).errorArgs())
        .containsEntry("value", "-1040.00")
        .containsEntry("expected", "-1060.00");
  }

  // A loan statement with negative balances in parentheses: two bookings, the second stating
  // balance, then a closing balance line stating closing.
  private static byte[] loanStatement(String balance, String closing) throws IOException {
    return SyntheticStatements.statement(
        List.of(
            List.of(
                List.of(at(40, GENERIC_TITLE)),
                List.of(at(40, "02.01.2031 Rent (100.00) (1100.00)")),
                List.of(at(40, "03.01.2031 Salary 50.00 " + balance)),
                List.of(at(40, "Closing balance " + closing)))));
  }

  /**
   * PR #281 review: a statement prints the balance at the foot of a page and again at the top of
   * the next, between a booking split across the page and its continuation lines there. The
   * carry-forward line at the top carries the booking over; without one, a line on the next page
   * after the balance line at the foot of the page before continues nothing.
   */
  @Test
  void aCarryForwardAtThePageTopKeepsTheContinuationOfASplitBooking() throws IOException {
    ImportTemplateDefinition template =
        spkTemplate(
            spkTemplate().pdfLayout().toBuilder()
                .withBalanceLinePattern("^(?:Kontostand am|Uebertrag)")
                .build());

    List<ParsedImportRow> rows = parser.parse(splitAcrossPages("Uebertrag"), template, null).rows();

    assertThat(rows)
        .extracting(row -> row.canonical().description())
        .containsExactly("Rate Januar Erika Beispiel", "Zinsen Januar");
    assertThat(rows.get(0).rawData())
        .containsEntry(
            ImportFileParserService.RAW_LINE_KEY, "02.01.2031Rate Januar 250,00\nErika Beispiel");

    List<ParsedImportRow> closed = parser.parse(splitAcrossPages(null), template, null).rows();

    assertThat(closed)
        .extracting(row -> row.canonical().description())
        .containsExactly("Rate Januar", "Zinsen Januar");
  }

  // Two pages in the SPK-6 layout: a booking at the foot of the first, a carry-forward line below
  // it, then the second page opening with the carry-forward line pageTop (none for null), the
  // booking's continuation line, and a booking.
  private static byte[] splitAcrossPages(String pageTop) throws IOException {
    List<List<SyntheticStatements.Cell>> second = new ArrayList<>();
    second.add(List.of(at(40, SPK_TITLE)));
    if (pageTop != null) {
      second.add(List.of(at(40, pageTop), endingAt(500, "-9.750,00")));
    }
    second.add(List.of(at(60, "Erika Beispiel")));
    second.add(List.of(at(40, "02.02.2031Zinsen Januar"), endingAt(500, "-31,25")));
    second.add(List.of(atFoot(40, "Seite 2 von 2")));
    return SyntheticStatements.statement(
        List.of(
            List.of(
                List.of(at(40, SPK_TITLE)),
                List.of(at(40, "02.01.2031Rate Januar"), endingAt(500, "250,00")),
                List.of(at(40, "Uebertrag"), endingAt(500, "-9.750,00")),
                List.of(atFoot(40, "Seite 1 von 2"))),
            second));
  }

  /**
   * PR #281 review: a line the record-start pattern finds below a table header, before the first
   * section, is an error row, so a section whose title the section pattern misses does not vanish
   * without a trace. A dated line of the summary above the table header, or one that states a
   * balance, is no row.
   */
  @Test
  void aBookingLineBeforeTheFirstSectionIsAnErrorRow() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, SyntheticStatements.YUH_MARKER)),
                    List.of(at(40, "01.01.2031 Kontoeroeffnung")),
                    List.of(at(40, "Account statement in CHF")),
                    SyntheticStatements.header(0),
                    List.of(at(40, "01.01.2031 Anfangsbestand 100.00")),
                    SyntheticStatements.booking(
                        0, "02.01.2031", "Lohn", "0000000001", null, "10.00", "110.00"),
                    List.of(at(40, "Kontoauszug in EUR")),
                    List.of(at(40, "Saldo per 01.01.2031"), endingAt(560, "50.00 EUR")),
                    SyntheticStatements.booking(
                        0, "03.01.2031", "Zahlung", "0000000002", "20.00", null, "30.00"))));

    List<ParsedImportRow> rows = parser.parse(statement, yuhTemplate(), null).rows();

    assertThat(rows).extracting(ParsedImportRow::isParsed).containsExactly(false, true);
    assertThat(rows.get(0).errorCode()).isEqualTo(ImportRowErrorValues.LINE_BEFORE_SECTION);
    assertThat(rows.get(0).errorArgs()).containsKey("value");
    assertThat(rows.get(1).canonical().currency()).isEqualTo("EUR");
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
    // The raw data keeps the whole line under #line, so no cell may take that name.
    assertInvalid(
        yuhTemplate(withLabels(yuh, List.of("DATUM", "#Line"))), "pdfLayout.headerLabels");
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
    // OCR reads no page, so a page footer cannot be told from a continuation line.
    assertInvalid(
        yuhTemplate(
            yuh.toBuilder().withHeaderLabels(List.of()).withContinuationColumn("Zeile").build(),
            "PDF_OCR"),
        "pdfLayout.continuationColumn");
    // A continuation end pattern needs continuation lines to end, and a pattern.
    assertInvalid(
        yuhTemplate(
            yuh.toBuilder().withContinuationColumn(null).withContinuationEndPattern("^x").build()),
        "pdfLayout.continuationEndPattern");
    assertInvalid(
        yuhTemplate(yuh.toBuilder().withContinuationEndPattern(" ").build()),
        "pdfLayout.continuationEndPattern");
  }

  /**
   * #268: a balance written in a form the amount rule does not read (here a trailing minus, as
   * German statements often print a negative balance) states nothing. The booking beside it parses,
   * as the balance column only checks it, and the running balance goes on from its amount, so the
   * next balance that is an amount checks it too.
   */
  @Test
  void aBalanceThatIsNoAmountLeavesItsBookingUnchecked() throws IOException {
    List<ParsedImportRow> rows =
        parser.parse(trailingMinusBalance("10.00"), yuhTemplate(), null).rows();

    assertThat(rows).allSatisfy(row -> assertThat(row.isParsed()).as(row.toString()).isTrue());
    assertThat(rows)
        .extracting(row -> row.canonical().amount())
        .containsExactly(new BigDecimal("-150.00"), new BigDecimal("60.00"));

    List<ParsedImportRow> misprinted =
        parser.parse(trailingMinusBalance("11.00"), yuhTemplate(), null).rows();

    assertThat(misprinted).extracting(ParsedImportRow::isParsed).containsExactly(true, false);
    assertThat(misprinted.get(1).errorCode()).isEqualTo(ImportRowErrorValues.BALANCE_MISMATCH);
    assertThat(misprinted.get(1).errorArgs())
        .containsEntry("value", "11.00")
        .containsEntry("expected", "10.00");
  }

  // A CHF section opening at 100.00: a debit of 150.00 to "50.00-", then a credit of 60.00 to
  // secondBalance.
  private static byte[] trailingMinusBalance(String secondBalance) throws IOException {
    return oneSection(
        "100.00",
        SyntheticStatements.booking(
            0, "02.01.2031", "Miete", "0000000001", "150.00", null, "50.00-"),
        SyntheticStatements.booking(
            0, "03.01.2031", "Lohn", "0000000002", null, "60.00", secondBalance));
  }

  /**
   * #268: a header line that the record-start pattern finds too is reported like the other
   * ambiguous lines: read as a header line, a booking holding every label would otherwise vanish
   * without a trace. It still sets the columns of the bookings below it.
   */
  @Test
  void aHeaderLineTheRecordStartPatternFindsTooIsAnErrorRow() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, GENERIC_TITLE)),
                    List.of(
                        at(40, "31.12."),
                        at(120, "Date"),
                        at(250, "Description"),
                        at(450, "Amount")),
                    List.of(at(120, "01.01.2031"), at(250, "Payment"), at(450, "10.00")))));

    List<ParsedImportRow> rows = parser.parse(statement, genericTemplate(), null).rows();

    assertThat(rows).extracting(ParsedImportRow::isParsed).containsExactly(false, true);
    assertThat(rows.get(0).errorCode()).isEqualTo(ImportRowErrorValues.LINE_AMBIGUOUS);
    assertThat(rows.get(0).errorArgs())
        .containsEntry("value", "31.12. Date Description Amount")
        .containsEntry("pattern", "headerLabels");
    assertThat(rows.get(1).canonical().amount()).isEqualByComparingTo("10.00");
    assertThat(rows.get(1).canonical().description()).isEqualTo("Payment");
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

  // A table of three labels, from x = 40 on, shifted by shift.
  private static List<SyntheticStatements.Cell> genericHeader(float shift) {
    return List.of(
        at(40 + shift, "Date"), at(150 + shift, "Description"), at(400 + shift, "Amount"));
  }

  // Every line one cell, the words under each label, continuation lines under Description.
  private static ImportTemplateDefinition genericTemplate() {
    ImportPdfLayout layout =
        ImportPdfLayout.builder(List.of("Line"), "(.*)", GENERIC_TITLE, "^\\d{2}\\.")
            .withHeaderLabels(List.of("Date", "Description", "Amount"))
            .withContinuationColumn("Description")
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

  // The SPK-6 layout: the date glued to the text, signed amounts, no header labels.
  private static ImportTemplateDefinition spkTemplate() {
    return spkTemplate(
        ImportPdfLayout.builder(
                List.of("Datum", "Text", "Betrag"),
                "(\\d{2}\\.\\d{2}\\.\\d{4})(.+?)\\s+(-?[\\d.]+,\\d{2})",
                SPK_TITLE,
                "^\\d{2}\\.\\d{2}\\.\\d{4}")
            .withContinuationColumn("Text")
            .withBalanceLinePattern("^Kontostand am")
            .build());
  }

  private static ImportTemplateDefinition spkTemplate(ImportPdfLayout layout) {
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

  // A two-page statement in the YUH-1 layout whose CHF section's marker and table header repeat at
  // the top of the second page. With an opening balance there, the second page opens a new CHF
  // section with it; without one, the first page's last booking continues there. The second page's
  // booking states bonusBalance, as does the closing balance below it.
  private static byte[] chfOnTwoPages(String secondOpening, String bonusBalance)
      throws IOException {
    List<List<SyntheticStatements.Cell>> first = new ArrayList<>();
    first.add(List.of(at(40, SyntheticStatements.YUH_MARKER)));
    first.add(List.of(at(40, "Kontoauszug in CHF")));
    first.add(List.of(at(40, "Saldo per 01.01.2031"), endingAt(560, "100.00 CHF")));
    first.add(SyntheticStatements.header(0));
    first.add(
        SyntheticStatements.booking(
            0, "02.01.2031", "Lohn", "0000000001", null, "10.00", "110.00"));
    first.add(
        SyntheticStatements.booking(
            0, "03.01.2031", "Miete", "0000000002", "20.00", null, "90.00"));
    first.add(List.of(atFoot(40, "Seite 1 von 2")));

    List<List<SyntheticStatements.Cell>> second = new ArrayList<>();
    second.add(List.of(at(40, SyntheticStatements.YUH_MARKER)));
    second.add(List.of(at(40, "Kontoauszug in CHF")));
    if (secondOpening != null) {
      second.add(List.of(at(40, "Saldo per 01.02.2031"), endingAt(560, secondOpening + " CHF")));
    }
    second.add(SyntheticStatements.header(0));
    if (secondOpening == null) {
      second.add(List.of(at(100, "Vermieter AG")));
    }
    second.add(
        SyntheticStatements.booking(
            0, "04.01.2031", "Bonus", "0000000003", null, "5.00", bonusBalance));
    second.add(List.of(at(40, "Saldo per 31.01.2031"), endingAt(560, bonusBalance + " CHF")));
    second.add(List.of(atFoot(40, "Seite 2 von 2")));
    return SyntheticStatements.statement(List.of(first, second));
  }

  private static ImportPdfLayout withLabels(ImportPdfLayout layout, List<String> labels) {
    return layout.toBuilder()
        .withHeaderLabels(labels)
        .withContinuationColumn(null)
        .withSectionPattern(null)
        .withSectionColumn(null)
        .withBalanceLinePattern(null)
        .withBalanceColumn(null)
        .build();
  }

  private static ImportPdfLayout withOptional(
      ImportPdfLayout layout, String continuation, String section, String balance) {
    return layout.toBuilder()
        .withContinuationColumn(continuation)
        .withSectionPattern(section)
        .withBalanceColumn(balance)
        .build();
  }

  private void assertInvalid(ImportTemplateDefinition template, String field) {
    assertThatThrownBy(() -> parser.validateTemplate(template, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getBody().getProperties()).containsEntry("field", field));
  }
}
