package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.dto.TransactionImportBatchResponse;
import com.trackmywealth.backend.dto.TransactionReadAccountDetails;
import com.trackmywealth.backend.dto.TransactionReadContext;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ImportTemplate;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.ImportBatchRepository;
import com.trackmywealth.backend.repository.ImportTemplateRepository;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-07-37: enrichment shared by account and workspace transaction lists and a direct read.
 *
 * <p>One account/portfolio query and at most two bulk import queries for an arbitrary page; never a
 * query per transaction. Access to the transactions is checked by the caller first. Raw source JSON
 * and imported statement content are never returned.
 */
@Service
public class TransactionReadContextService {

  // Both PDF template kinds are one public format: the client cares about the file, not the reader.
  private static final Map<String, String> PUBLIC_TEMPLATE_FORMATS =
      Map.of(
          ImportTemplateValues.FORMAT_CSV, TransactionImportBatchResponse.FORMAT_CSV,
          ImportTemplateValues.FORMAT_SPREADSHEET,
              TransactionImportBatchResponse.FORMAT_SPREADSHEET,
          ImportTemplateValues.FORMAT_PDF_TEXT, TransactionImportBatchResponse.FORMAT_PDF,
          ImportTemplateValues.FORMAT_PDF_OCR, TransactionImportBatchResponse.FORMAT_PDF);

  private final EntityManager entityManager;
  private final ImportBatchRepository batchRepository;
  private final ImportTemplateRepository templateRepository;

  public TransactionReadContextService(
      EntityManager entityManager,
      ImportBatchRepository batchRepository,
      ImportTemplateRepository templateRepository) {
    this.entityManager = entityManager;
    this.batchRepository = batchRepository;
    this.templateRepository = templateRepository;
  }

  @Transactional(readOnly = true)
  public TransactionReadContext load(List<Transaction> transactions) {
    if (transactions.isEmpty()) {
      return TransactionReadContext.EMPTY;
    }
    Set<UUID> accountIds = new HashSet<>();
    Set<UUID> batchIds = new HashSet<>();
    for (Transaction transaction : transactions) {
      Account account = transaction.getAccount();
      accountIds.add(account.getId());
      if (transaction.getCounterpartyAccountId() != null) {
        accountIds.add(transaction.getCounterpartyAccountId());
      }
      if (transaction.getImportBatchId() != null) {
        batchIds.add(transaction.getImportBatchId());
      }
    }

    Map<UUID, TransactionReadAccountDetails> accounts = new HashMap<>();
    entityManager
        .createQuery(
            "SELECT a.id, a.name, p.id, p.name FROM Account a JOIN a.portfolio p"
                + " WHERE a.id IN :ids",
            Object[].class)
        .setParameter("ids", accountIds)
        .getResultList()
        .forEach(
            row ->
                accounts.put(
                    (UUID) row[0],
                    new TransactionReadAccountDetails(
                        (String) row[1], (UUID) row[2], (String) row[3])));

    if (batchIds.isEmpty()) {
      return new TransactionReadContext(accounts, Map.of());
    }
    List<ImportBatch> batches = batchRepository.findAllById(batchIds);
    Set<UUID> templateIds = new HashSet<>();
    for (ImportBatch batch : batches) {
      if (batch.getTemplateId() != null) {
        templateIds.add(batch.getTemplateId());
      }
    }
    Map<UUID, String> templateFormats = new HashMap<>();
    if (!templateIds.isEmpty()) {
      for (ImportTemplate template : templateRepository.findAllById(templateIds)) {
        templateFormats.put(template.getId(), template.getFileFormat());
      }
    }
    Map<UUID, TransactionImportBatchResponse> imports = new HashMap<>();
    for (ImportBatch batch : batches) {
      imports.put(
          batch.getId(),
          new TransactionImportBatchResponse(
              batch.getId(),
              formatOf(batch, templateFormats.get(batch.getTemplateId())),
              batch.getUploadedAt()));
    }
    return new TransactionReadContext(accounts, imports);
  }

  /**
   * Normalizes internal formats to the public import vocabulary. A batch that names a template
   * reports that template's format (a template a batch used is never deleted); one without a
   * template is a CAMT statement (#350) when it is a document, otherwise a CSV file. Anything else
   * cannot pass the database's check constraints, so it fails loudly instead of being mislabelled.
   */
  static String formatOf(ImportBatch batch, @Nullable String templateFormat) {
    if (templateFormat != null) {
      String format = PUBLIC_TEMPLATE_FORMATS.get(templateFormat);
      if (format == null) {
        throw new IllegalStateException("Unknown import template format " + templateFormat);
      }
      return format;
    }
    return switch (batch.getSourceKind()) {
      case ImportBatchValues.SOURCE_DOCUMENT -> TransactionImportBatchResponse.FORMAT_CAMT;
      case ImportBatchValues.SOURCE_CSV -> TransactionImportBatchResponse.FORMAT_CSV;
      default ->
          throw new IllegalStateException("Unknown import source kind " + batch.getSourceKind());
    };
  }
}
