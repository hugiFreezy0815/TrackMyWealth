package com.trackmywealth.backend.service;

import static com.trackmywealth.backend.service.SyntheticStatements.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.trackmywealth.backend.dto.ImportPdfLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

class PdfContinuationTest {
  private final PdfImportReaderService reader =
      new PdfImportReaderService(mock(LocalOcrService.class));

  @Test
  void continuationAfterRepeatedTableHeader() throws Exception {
    var header = List.of(at(40, "Date"), at(150, "Description"), at(400, "Amount"));
    var pdf =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, "Invented statement")),
                    header,
                    List.of(at(40, "01.01.2031"), at(150, "Payment"), at(400, "10.00")),
                    List.of(at(40, "Page 1"))),
                List.of(
                    List.of(at(40, "Invented statement")),
                    List.of(at(240, "Date"), at(350, "Description"), at(600, "Amount")),
                    List.of(at(350, "Invented recipient")),
                    List.of(at(240, "02.01.2031"), at(350, "Other"), at(600, "20.00")),
                    List.of(at(40, "Page 2")))));
    var layout =
        new ImportPdfLayout(
            List.of("Line"),
            "(.*)",
            "Invented statement",
            "^\\d{2}\\.",
            List.of("Date", "Description", "Amount"),
            "Description",
            null,
            null,
            null,
            null);
    var template =
        new ImportFileParserServiceTest.Template()
            .pdf("PDF_TEXT", layout)
            .mapping(
                ImportFileParserServiceTest.mapping("Date", "Amount")
                    .description("Description")
                    .build())
            .dateFormat("dd.MM.yyyy")
            .build();
    var bookings = reader.readBookingLines(pdf, template);
    assertThat(bookings.get(0).fullText()).contains("Invented recipient");
    assertThat(bookings.get(0).cells())
        .containsExactly(
            "01.01.2031 Payment 10.00", "01.01.2031", "Payment Invented recipient", "10.00");
    assertThat(bookings.get(1).cells())
        .containsExactly("02.01.2031 Other 20.00", "02.01.2031", "Other", "20.00");
    var rows = new ImportFileParserService(reader).parse(pdf, template, null).rows();
    assertThat(rows).allSatisfy(row -> assertThat(row.isParsed()).isTrue());
    assertThat(rows.get(0).canonical().description()).isEqualTo("Payment Invented recipient");
    assertThat(rows.get(0).rawData())
        .containsEntry(
            ImportFileParserService.RAW_LINE_KEY, "01.01.2031 Payment 10.00\nInvented recipient");
  }

  @Test
  void similarReferencesAreNotFurniture() throws Exception {
    var pdf =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, "Invented statement")),
                    List.of(at(40, "01.01.2031 Payment 10.00")),
                    List.of(at(60, "Reference 111111")),
                    List.of(at(40, "Page 1"))),
                List.of(
                    List.of(at(40, "Invented statement")),
                    List.of(at(40, "02.01.2031 Payment 20.00")),
                    List.of(at(60, "Reference 222222")),
                    List.of(at(40, "Page 2")))));
    var layout =
        new ImportPdfLayout(
            List.of("Line"),
            "(.*)",
            "Invented statement",
            "^\\d{2}\\.",
            List.of(),
            "Line",
            null,
            null,
            null,
            null);
    var template = new ImportFileParserServiceTest.Template().pdf("PDF_TEXT", layout).build();
    assertThat(reader.readBookingLines(pdf, template).get(0).fullText())
        .contains("Reference 111111");
  }

  @Test
  void shortReferencesAreNotFurniture() throws Exception {
    var pdf =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, "Invented statement")),
                    List.of(at(40, "01.01.2031 Payment 10.00")),
                    List.of(at(60, "Reference 111")),
                    List.of(at(40, "Page 1"))),
                List.of(
                    List.of(at(40, "Invented statement")),
                    List.of(at(40, "02.01.2031 Payment 20.00")),
                    List.of(at(60, "Reference 222")),
                    List.of(at(40, "Page 2")))));
    var layout =
        new ImportPdfLayout(
            List.of("Line"),
            "(.*)",
            "Invented statement",
            "^\\d{2}\\.",
            List.of(),
            "Line",
            null,
            null,
            null,
            null);
    var template = new ImportFileParserServiceTest.Template().pdf("PDF_TEXT", layout).build();
    assertThat(reader.readBookingLines(pdf, template).get(0).fullText()).contains("Reference 111");
  }
}
