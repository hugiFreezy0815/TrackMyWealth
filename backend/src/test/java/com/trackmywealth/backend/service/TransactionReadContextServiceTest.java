package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.TransactionReadContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ImportTemplate;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.ImportBatchRepository;
import com.trackmywealth.backend.repository.ImportTemplateRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** US-07-37: table-driven import provenance and fixed-query page enrichment. */
class TransactionReadContextServiceTest {

  private final EntityManager entityManager = mock(EntityManager.class);
  private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
  private final ImportTemplateRepository templates = mock(ImportTemplateRepository.class);
  private final TransactionReadContextService service =
      new TransactionReadContextService(entityManager, batches, templates);

  @Test
  void documentWithoutTemplateIsCamtAndPdfVariantsAreNormalized() {
    ImportBatch batch = mock(ImportBatch.class);
    when(batch.getSourceKind()).thenReturn("DOCUMENT");

    assertThat(TransactionReadContextService.formatOf(batch, null)).isEqualTo("CAMT");
    assertThat(
            TransactionReadContextService.formatOf(batch, ImportTemplateValues.FORMAT_PDF_TEXT))
        .isEqualTo("PDF");
    assertThat(
            TransactionReadContextService.formatOf(batch, ImportTemplateValues.FORMAT_PDF_OCR))
        .isEqualTo("PDF");
    assertThat(
            TransactionReadContextService.formatOf(
                batch, ImportTemplateValues.FORMAT_SPREADSHEET))
        .isEqualTo("SPREADSHEET");
    assertThat(TransactionReadContextService.formatOf(batch, ImportTemplateValues.FORMAT_CSV))
        .isEqualTo("CSV");
  }

  @Test
  void pageMetadataIsBulkLoadedAcrossAccountsAndBatches() {
    UUID accountId = UUID.randomUUID();
    UUID otherId = UUID.randomUUID();
    UUID portfolioId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    UUID templateId = UUID.randomUUID();
    OffsetDateTime uploaded = OffsetDateTime.parse("2026-10-21T10:00:00Z");

    Account account = mock(Account.class);
    when(account.getId()).thenReturn(accountId);
    Transaction first = new Transaction();
    first.setAccount(account);
    first.setCounterpartyAccountId(otherId);
    first.setImportBatchId(batchId);
    Transaction second = new Transaction();
    second.setAccount(account);
    second.setImportBatchId(batchId);

    @SuppressWarnings("unchecked")
    TypedQuery<Object[]> accountQuery = mock(TypedQuery.class);
    when(entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(accountQuery);
    when(accountQuery.setParameter(eq("ids"), any())).thenReturn(accountQuery);
    when(accountQuery.getResultList())
        .thenReturn(
            Arrays.asList(
                new Object[] {accountId, "Private account", portfolioId, "PostFinance"},
                new Object[] {otherId, "Tagesgeld", portfolioId, "PostFinance"}));

    ImportBatch batch = mock(ImportBatch.class);
    when(batch.getId()).thenReturn(batchId);
    when(batch.getTemplateId()).thenReturn(templateId);
    when(batch.getUploadedAt()).thenReturn(uploaded);
    when(batches.findAllById(any())).thenReturn(List.of(batch));
    ImportTemplate template = mock(ImportTemplate.class);
    when(template.getId()).thenReturn(templateId);
    when(template.getFileFormat()).thenReturn("PDF_TEXT");
    when(templates.findAllById(any())).thenReturn(List.of(template));

    var context = service.load(List.of(first, second));

    assertThat(context.accounts().get(accountId).portfolioName()).isEqualTo("PostFinance");
    assertThat(context.accounts().get(otherId).name()).isEqualTo("Tagesgeld");
    assertThat(context.imports().get(batchId).fileFormat()).isEqualTo("PDF");
    assertThat(context.imports().get(batchId).uploadedAt()).isEqualTo(uploaded);
    verify(entityManager, times(1)).createQuery(anyString(), eq(Object[].class));
    verify(batches, times(1)).findAllById(any());
    verify(templates, times(1)).findAllById(any());
  }

  @Test
  void emptyPageSkipsEveryQuery() {
    assertThat(service.load(List.of())).isEqualTo(TransactionReadContext.EMPTY);
    org.mockito.Mockito.verifyNoInteractions(entityManager, batches, templates);
  }
}
