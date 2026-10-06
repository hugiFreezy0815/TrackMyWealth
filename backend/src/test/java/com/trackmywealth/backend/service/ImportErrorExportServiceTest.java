package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * FR-IMP-012: how one cell of the error export is written. The whole file, in the source's
 * delimiter and encoding, is {@code ImportBatchControllerTest}'s part.
 */
class ImportErrorExportServiceTest {

  @Test
  void aPlainCellIsWrittenAsItIs() {
    assertThat(ImportErrorExportService.cell("Einkauf", ";")).isEqualTo("Einkauf");
    assertThat(ImportErrorExportService.cell(null, ";")).isEmpty();
  }

  @Test
  void aCellIsQuotedWhenItHoldsTheDelimiterAQuoteOrALineBreak() {
    assertThat(ImportErrorExportService.cell("Miete; Wohnung", ";"))
        .isEqualTo("\"Miete; Wohnung\"");
    assertThat(ImportErrorExportService.cell("say \"hi\"", ";")).isEqualTo("\"say \"\"hi\"\"\"");
    assertThat(ImportErrorExportService.cell("two\nlines", ";")).isEqualTo("\"two\nlines\"");
    assertThat(ImportErrorExportService.cell("-85,00", ",")).isEqualTo("\"-85,00\"");
    assertThat(ImportErrorExportService.cell("-85,00", ";")).isEqualTo("-85,00");
  }

  @Test
  void aCellASpreadsheetWouldRunAsAFormulaGetsALeadingApostrophe() {
    assertThat(ImportErrorExportService.cell("=HYPERLINK(1)", ";")).isEqualTo("'=HYPERLINK(1)");
    assertThat(ImportErrorExportService.cell("+1", ";")).isEqualTo("'+1");
    assertThat(ImportErrorExportService.cell("@SUM(A1)", ";")).isEqualTo("'@SUM(A1)");
    assertThat(ImportErrorExportService.cell("\tx", ";")).isEqualTo("'\tx");
    assertThat(ImportErrorExportService.cell("-cmd", ";")).isEqualTo("'-cmd");
    assertThat(ImportErrorExportService.cell("-", ";")).isEqualTo("'-");
  }

  @Test
  void aNegativeAmountStaysAnAmount() {
    assertThat(ImportErrorExportService.cell("-85.00", ";")).isEqualTo("-85.00");
    assertThat(ImportErrorExportService.cell("-.5", ";")).isEqualTo("-.5");
    assertThat(ImportErrorExportService.cell("-,5", ";")).isEqualTo("-,5");
  }
}
