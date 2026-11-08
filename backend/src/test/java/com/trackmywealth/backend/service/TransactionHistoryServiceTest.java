package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.TransactionHistoryEntryResponse;
import com.trackmywealth.backend.dto.TransactionHistoryEvent;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * US-07-38: events map to entries; import provenance uses #479's public file formats ({@link
 * TransactionReadContextService#formatOf}).
 */
class TransactionHistoryServiceTest {

  private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-21T09:12:00Z");

  @Test
  void anImportEventCarriesItsBatchWithThePublicFileFormat() {
    UUID batch = UUID.randomUUID();
    TransactionHistoryEntryResponse entry =
        TransactionHistoryService.toEntry(
            new TransactionHistoryEvent(
                AT, "IMPORTED", null, null, null, null, batch, AT, "PDF_OCR", "DOCUMENT", null));

    assertThat(entry.kind()).isEqualTo("IMPORTED");
    assertThat(entry.actorDisplayName()).isNull();
    assertThat(entry.importBatch().id()).isEqualTo(batch);
    assertThat(entry.importBatch().fileFormat()).as("the template's format").isEqualTo("PDF");
    assertThat(entry.importBatch().uploadedAt()).isEqualTo(AT);
  }

  @Test
  void anyOtherEventHasNoBatchAndKeepsItsFields() {
    UUID from = UUID.randomUUID();
    UUID to = UUID.randomUUID();
    UUID related = UUID.randomUUID();
    TransactionHistoryEntryResponse entry =
        TransactionHistoryService.toEntry(
            new TransactionHistoryEvent(
                AT, "VOIDED", "Anna Meier", from, to, related, null, null, null, null, "dup"));

    assertThat(entry)
        .isEqualTo(
            new TransactionHistoryEntryResponse(
                AT, "VOIDED", "Anna Meier", from, to, related, null, "dup"));
  }
}
