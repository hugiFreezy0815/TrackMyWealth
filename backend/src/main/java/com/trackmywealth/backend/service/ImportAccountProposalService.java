package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportAccountIdentifierRule;
import com.trackmywealth.backend.dto.ImportAccountProposalResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportFileAccountIdentifier;
import com.trackmywealth.backend.dto.ImportParseResult;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ImportTemplateParse;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ImportFile;
import com.trackmywealth.backend.entity.InstitutionCatalogue;
import com.trackmywealth.backend.repository.ImportFileRepository;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US-07-11: what a statement suggests for an account that does not exist yet, and the identifier it
 * names, for registering on the account once the member has created it. Read-only: the file is read
 * as a template dry run reads it ({@link ImportTemplateService#parseWithoutAccount}) and nothing is
 * stored. Account creation stays the member's, through the ordinary account endpoint.
 *
 * <p>The file is uploaded with a template, or it is the file of an open import batch (one whose
 * identification found no account, US-07-10), read with the template version the batch used.
 */
@Service
public class ImportAccountProposalService {

  static final String ACCOUNT_CASH = "CASH";
  static final String ACCOUNT_CREDIT_CARD = "CREDIT_CARD";

  // A batch still waiting for its commit: one already committed, discarded or rolled back is no
  // basis for a new account.
  private static final Set<String> OPEN =
      Set.of(ImportBatchValues.UPLOADED, ImportBatchValues.PARSED);

  // Transaction types only a card statement maps to. Not REFUND: a bank account has refunds too.
  private static final Set<String> CARD_TRANSACTION_TYPES =
      Set.of("CREDIT_CARD_PURCHASE", "SETTLEMENT");

  private final ImportTemplateService templates;
  private final ImportBatchService batches;
  private final ImportFileRepository files;
  private final InstitutionCatalogueRepository catalogue;
  private final TransactionTemplate readOnlyTransaction;

  public ImportAccountProposalService(
      ImportTemplateService templates,
      ImportBatchService batches,
      ImportFileRepository files,
      InstitutionCatalogueRepository catalogue,
      PlatformTransactionManager transactionManager) {
    this.templates = templates;
    this.batches = batches;
    this.files = files;
    this.catalogue = catalogue;
    this.readOnlyTransaction = new TransactionTemplate(transactionManager);
    this.readOnlyTransaction.setReadOnly(true);
  }

  /** The proposal for {@code content}, read with template version {@code templateId}. */
  public ImportAccountProposalResponse propose(
      UUID templateId, byte[] content, AuthenticatedUserPrincipal actor) {
    return proposal(templateId, templates.parseWithoutAccount(templateId, content, actor));
  }

  /**
   * The proposal for the file of open batch {@code batchId} of account {@code accountId}, which
   * needs access to that account, as reading its rows does.
   *
   * @throws com.trackmywealth.backend.error.ApiException 409 {@code IMPORT_BATCH_STATE} unless the
   *     batch is {@code UPLOADED} or {@code PARSED} with a template and its file
   */
  public ImportAccountProposalResponse proposeFromBatch(
      UUID accountId, UUID batchId, AuthenticatedUserPrincipal actor) {
    ImportBatch batch = openBatch(accountId, batchId, actor);
    return proposal(
        batch.getTemplateId(),
        templates.parseWithoutAccount(batch.getTemplateId(), fileOf(batch), actor));
  }

  /**
   * The identifier the file of open batch {@code batchId} names, normalized, to register on another
   * account: {@code null} when its template does not identify accounts. Never log or return its
   * value (US-07-10).
   */
  public ImportFileAccountIdentifier identifierOf(
      UUID accountId, UUID batchId, AuthenticatedUserPrincipal actor) {
    ImportBatch batch = openBatch(accountId, batchId, actor);
    return templates
        .parseWithoutAccount(batch.getTemplateId(), fileOf(batch), actor)
        .result()
        .accountIdentifier();
  }

  // --- the proposal ----------------------------------------------------------------------------

  private ImportAccountProposalResponse proposal(UUID templateId, ImportTemplateParse parse) {
    UUID catalogueId = parse.institutionCatalogueId();
    String institutionName =
        catalogueId == null
            ? null
            : readOnlyTransaction.execute(
                status ->
                    catalogue
                        .findById(catalogueId)
                        .map(InstitutionCatalogue::getName)
                        .orElse(null));
    return new ImportAccountProposalResponse(
        templateId,
        catalogueId,
        institutionName,
        accountTypeOf(parse.definition()),
        currencyOf(parse.definition(), parse.result()),
        masked(parse.result().accountIdentifier()));
  }

  /**
   * A credit card when the template reads a masked card number as the account's identifier
   * (US-07-10), or else maps a type only a card statement has; otherwise an ordinary account.
   */
  static String accountTypeOf(ImportTemplateDefinition template) {
    ImportAccountIdentifierRule rule = template.accountIdentifier();
    boolean card =
        rule != null && ImportTemplateValues.IDENTIFIER_MASKED_CARD.equals(rule.kind())
            || template.typeMapping().values().stream().anyMatch(CARD_TRANSACTION_TYPES::contains)
            || template.descriptionTypePatterns().keySet().stream()
                .anyMatch(CARD_TRANSACTION_TYPES::contains);
    return card ? ACCOUNT_CREDIT_CARD : ACCOUNT_CASH;
  }

  /**
   * The one currency every readable row is in; a fixed-currency template's own when no row could be
   * read; otherwise none. A template that takes the currency from the account reads none here.
   */
  static String currencyOf(ImportTemplateDefinition template, ImportParseResult result) {
    Set<String> currencies =
        result.rows().stream()
            .map(ParsedImportRow::canonical)
            .filter(Objects::nonNull)
            .map(CanonicalImportRow::currency)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (currencies.isEmpty()
        && ImportTemplateValues.CURRENCY_FIXED.equals(template.currencyMode())) {
      return template.fixedCurrency();
    }
    return currencies.size() == 1 ? currencies.iterator().next() : null;
  }

  /**
   * The display value of the file's identifier, as an account's ({@link AccountIdentifierService}).
   */
  static String masked(ImportFileAccountIdentifier identifier) {
    return identifier == null || !identifier.found()
        ? null
        : AccountIdentifierService.masked(identifier.value(), identifier.kind());
  }

  // --- the batch's file ------------------------------------------------------------------------

  private ImportBatch openBatch(UUID accountId, UUID batchId, AuthenticatedUserPrincipal actor) {
    ImportBatch batch =
        readOnlyTransaction.execute(
            status ->
                batches.requireBatch(
                    batches.requireEditableAccount(accountId, actor), batchId, actor));
    if (!OPEN.contains(batch.getStatus()) || batch.getTemplateId() == null) {
      throw ImportBatchService.stateConflict(batch);
    }
    return batch;
  }

  // import_file has its own RLS policy: read in a transaction, which sets the workspace context.
  private byte[] fileOf(ImportBatch batch) {
    return readOnlyTransaction.execute(
        status ->
            files
                .findById(batch.getId())
                .map(ImportFile::getContent)
                .orElseThrow(() -> ImportBatchService.stateConflict(batch)));
  }
}
