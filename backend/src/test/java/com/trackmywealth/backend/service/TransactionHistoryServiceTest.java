package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** US-07-38: public import provenance uses stable, non-sensitive file-format values. */
class TransactionHistoryServiceTest {

  @Test
  void fileFormatUsesTemplateWhenPresentAndCamtForDocumentWithoutTemplate() {
    assertThat(TransactionHistoryService.fileFormat("PDF_TEXT", "DOCUMENT")).isEqualTo("PDF");
    assertThat(TransactionHistoryService.fileFormat("PDF_OCR", "DOCUMENT")).isEqualTo("PDF");
    assertThat(TransactionHistoryService.fileFormat("CSV", "FILE")).isEqualTo("CSV");
    assertThat(TransactionHistoryService.fileFormat("SPREADSHEET", "FILE"))
        .isEqualTo("SPREADSHEET");
    assertThat(TransactionHistoryService.fileFormat(null, "DOCUMENT")).isEqualTo("CAMT");
  }
}
