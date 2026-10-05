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
    ImportPdfLayout yuh = SyntheticStatements.yuhLayout();
    ImportPdfLayout unchecked =
        new ImportPdfLayout(
            yuh.columns(),
            yuh.rowPattern(),
            yuh.documentMarker(),
            yuh.recordStartPattern(),
            yuh.headerLabels(),
            yuh.continuationColumn(),
            yuh.sectionPattern(),
            yuh.sectionColumn(),
            yuh.balanceLinePattern(),
            null);

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
    String title = "Invented Sparkasse Darlehenskonto";
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, title)),
                    List.of(at(40, "Kontostand am 01.01.2031"), endingAt(500, "-10.000,00")),
                    List.of(at(40, "02.01.2031Rate Januar"), endingAt(500, "250,00")),
                    List.of(at(60, "Erika Beispiel")),
                    List.of(at(40, "Seite 1 von 2"))),
                List.of(
                    List.of(at(40, title)),
                    List.of(at(40, "02.02.2031Zinsen Januar"), endingAt(500, "-31,25")),
                    List.of(at(40, "Kontostand am 28.02.2031"), endingAt(500, "-9.781,25")),
                    List.of(at(40, "Seite 2 von 2")))));
    ImportPdfLayout layout =
        new ImportPdfLayout(
            List.of("Datum", "Text", "Betrag"),
            "(\\d{2}\\.\\d{2}\\.\\d{4})(.+?)\\s+(-?[\\d.]+,\\d{2})",
            title,
            "^\\d{2}\\.\\d{2}\\.\\d{4}",
            List.of(),
            "Text",
            null,
            null,
            "^Kontostand am",
            null);
    ImportTemplateDefinition template =
        new ImportFileParserServiceTest.Template()
            .pdf("PDF_TEXT", layout)
            .mapping(
                ImportFileParserServiceTest.mapping("Datum", "Betrag").description("Text").build())
            .dateFormat("dd.MM.yyyy")
            .decimal(",")
            .thousands(".")
            .build();

    List<ParsedImportRow> rows = parser.parse(statement, template, null).rows();

    assertThat(rows)
        .extracting(row -> row.canonical().description())
        .containsExactly("Rate Januar Erika Beispiel", "Zinsen Januar");
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
        new ImportPdfLayout(yuh.columns(), yuh.rowPattern(), yuh.documentMarker(), "^\\d");
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

  private static ImportPdfLayout withLabels(ImportPdfLayout layout, List<String> labels) {
    return new ImportPdfLayout(
        layout.columns(),
        layout.rowPattern(),
        layout.documentMarker(),
        layout.recordStartPattern(),
        labels,
        null,
        null,
        null,
        null,
        null);
  }

  private static ImportPdfLayout withOptional(
      ImportPdfLayout layout, String continuation, String section, String balance) {
    return new ImportPdfLayout(
        layout.columns(),
        layout.rowPattern(),
        layout.documentMarker(),
        layout.recordStartPattern(),
        layout.headerLabels(),
        continuation,
        section,
        layout.sectionColumn(),
        layout.balanceLinePattern(),
        balance);
  }

  private void assertInvalid(ImportTemplateDefinition template, String field) {
    assertThatThrownBy(() -> parser.validateTemplate(template, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getBody().getProperties()).containsEntry("field", field));
  }
}
