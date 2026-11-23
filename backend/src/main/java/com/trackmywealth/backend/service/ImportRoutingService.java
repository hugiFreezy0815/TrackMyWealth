package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.IdempotencyClaim;
import com.trackmywealth.backend.dto.ImportAccountCandidateResponse;
import com.trackmywealth.backend.dto.ImportAccountContext;
import com.trackmywealth.backend.dto.ImportAccountProposalResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportFileAccountIdentifier;
import com.trackmywealth.backend.dto.ImportRoutingResponse;
import com.trackmywealth.backend.dto.ImportRoutingValues;
import com.trackmywealth.backend.dto.ImportTemplateCandidateResponse;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ImportTemplateParse;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.dto.ImportUpload;
import com.trackmywealth.backend.dto.PortfolioPlacement;
import com.trackmywealth.backend.dto.ResolvedImportTemplate;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US-07-34 (#365): a statement uploaded without an account. The server finds its format as an
 * account upload does (the one exact header match, or a CAMT statement; FR-IMP-022) and then its
 * account from the identifier the file names (US-07-10):
 *
 * <ul>
 *   <li>an IBAN or contract number of one account the member may import into: the preview batch is
 *       created there, through the ordinary upload ({@link ImportBatchService#upload}) with its
 *       limits, identification and duplicate check;
 *   <li>otherwise nothing is stored. The answer lists candidates - the account a masked card number
 *       names (never routed on its own), then the importable accounts of the statement's kind at
 *       the template's bank - and, when the file names no account the member may import into, the
 *       US-07-11 proposal for a new one;
 *   <li>a file no template reads unambiguously stores nothing either: the answer lists the
 *       templates that can read it, and the member uploads again naming one.
 * </ul>
 *
 * An account the member may not import into is never named or listed, so the answer reveals nothing
 * about it. Routing only proposes: every batch goes through the preview and the member's commit.
 */
@Service
public class ImportRoutingService {

  // A CAMT statement reads the same for any kind of account until it has one: the reader takes
  // each entry's currency from the entry, and the kind only types entries the file leaves untyped.
  private static final ImportAccountContext NO_ACCOUNT =
      new ImportAccountContext(null, false, false);

  private final ImportTemplateService templates;
  private final CamtImportReaderService camtReader;
  private final ImportAccountIdentificationService identification;
  private final ImportAccountProposalService proposals;
  private final ImportBatchService batches;
  private final AccessControlService accessControlService;
  private final AccountService accountService;
  private final AccountRepository accountRepository;
  private final TransactionTemplate readOnlyTransaction;

  public ImportRoutingService(
      ImportTemplateService templates,
      CamtImportReaderService camtReader,
      ImportAccountIdentificationService identification,
      ImportAccountProposalService proposals,
      ImportBatchService batches,
      AccessControlService accessControlService,
      AccountService accountService,
      AccountRepository accountRepository,
      PlatformTransactionManager transactionManager) {
    this.templates = templates;
    this.camtReader = camtReader;
    this.identification = identification;
    this.proposals = proposals;
    this.batches = batches;
    this.accessControlService = accessControlService;
    this.accountService = accountService;
    this.accountRepository = accountRepository;
    this.readOnlyTransaction = new TransactionTemplate(transactionManager);
    this.readOnlyTransaction.setReadOnly(true);
  }

  /**
   * Routes {@code content}, read with {@code templateId} when given. {@code claim} is the upload's
   * {@code Idempotency-Key} claim, completed with the batch when one is created; may be {@code
   * null}.
   *
   * @throws ImportFileRejectedException 422 for an empty file, or one the template cannot read
   */
  public ImportRoutingResponse route(
      String fileName,
      String mediaType,
      byte[] content,
      @Nullable UUID templateId,
      AuthenticatedUserPrincipal actor,
      @Nullable IdempotencyClaim claim) {
    readOnlyTransaction.executeWithoutResult(
        status -> accessControlService.requireActingMember(actor));
    if (content.length == 0) {
      throw new ImportFileRejectedException(ProblemCode.IMPORT_FILE_EMPTY, "The file is empty.");
    }
    // An .xlsx workbook is a ZIP archive too, but no CAMT statement (#273).
    if (templateId == null
        && camtReader.isCamtFile(content)
        && !SpreadsheetImportReaderService.isWorkbook(content)) {
      ImportFileAccountIdentifier identifier =
          camtReader.parse(content, NO_ACCOUNT).accountIdentifier();
      return route(
          new ImportUpload(fileName, mediaType, null, actor, claim),
          content,
          identifier,
          null,
          account -> !account.isHasStatementCycle(),
          () -> null);
    }
    UUID chosen = templateId;
    if (chosen == null) {
      List<ImportTemplateCandidateResponse> candidates = templates.detect(content, actor);
      chosen = ImportBatchService.singleExactMatch(candidates);
      if (chosen == null) {
        return ImportRoutingResponse.noTemplate(candidates);
      }
    }
    // Refuses a template version no new import may use, as the account upload does.
    ResolvedImportTemplate template = templates.resolveForImport(chosen, actor);
    ImportTemplateDefinition definition = template.definition();
    // A holdings statement (#273) has no rows to read and names no account: never routed.
    if (definition.isSnapshot()) {
      return route(
          new ImportUpload(fileName, mediaType, chosen, actor, claim),
          content,
          null,
          templates.withoutParse(chosen, actor).institutionCatalogueId(),
          Account::isHoldsPositions,
          () -> null);
    }
    ImportTemplateParse parse = templates.parseWithoutAccount(chosen, content, actor);
    UUID readWith = chosen;
    return route(
        new ImportUpload(fileName, mediaType, chosen, actor, claim),
        content,
        ImportBatchService.fileIdentifier(template, parse.result()),
        parse.institutionCatalogueId(),
        ofTheStatementsKind(definition),
        () -> proposals.proposal(readWith, parse));
  }

  private ImportRoutingResponse route(
      ImportUpload upload,
      byte[] content,
      @Nullable ImportFileAccountIdentifier identifier,
      @Nullable UUID catalogueInstitutionId,
      Predicate<Account> sameKind,
      Supplier<@Nullable ImportAccountProposalResponse> proposal) {
    AuthenticatedUserPrincipal actor = upload.actor();
    UUID workspaceId = actor.workspaceId();
    Optional<UUID> named =
        readOnlyTransaction.execute(
            status ->
                identification
                    .importableAccountNamed(workspaceId, identifier, actor)
                    .map(Account::getId));
    if (named.isPresent() && !masked(identifier)) {
      ImportBatchResponse batch =
          batches.upload(
              named.get(),
              upload.fileName(),
              upload.mediaType(),
              content,
              upload.templateId(),
              null,
              actor,
              upload.claim());
      return ImportRoutingResponse.created(batch);
    }
    List<ImportAccountCandidateResponse> candidates =
        readOnlyTransaction.execute(
            status ->
                candidates(
                    workspaceId, named.orElse(null), catalogueInstitutionId, sameKind, actor));
    // A proposal for a new account only when the file names none the member may import into.
    return ImportRoutingResponse.unrouted(candidates, named.isPresent() ? null : proposal.get());
  }

  // The account the file names by a masked card number first, then the bank's accounts of the
  // statement's kind; only those the member may import into.
  private List<ImportAccountCandidateResponse> candidates(
      UUID workspaceId,
      @Nullable UUID namedId,
      @Nullable UUID catalogueInstitutionId,
      Predicate<Account> sameKind,
      AuthenticatedUserPrincipal actor) {
    List<Account> listed = new ArrayList<>();
    if (namedId != null) {
      accountRepository.findById(namedId).ifPresent(listed::add);
    }
    if (catalogueInstitutionId != null) {
      listed.addAll(
          identification.importable(
              actor,
              accountRepository
                  .findActiveAtCatalogueInstitution(workspaceId, catalogueInstitutionId)
                  .stream()
                  .filter(sameKind)
                  .filter(account -> !account.getId().equals(namedId))
                  .toList()));
    }
    Map<UUID, PortfolioPlacement> placements = accountService.portfolioPlacementsOf(listed);
    return listed.stream()
        .map(
            account ->
                candidate(
                    account,
                    placements.get(account.getId()),
                    account.getId().equals(namedId)
                        ? ImportRoutingValues.REASON_IDENTIFIER
                        : ImportRoutingValues.REASON_INSTITUTION))
        .toList();
  }

  private static ImportAccountCandidateResponse candidate(
      Account account, PortfolioPlacement placement, String reason) {
    String identifierMasked = account.getIdentifierMasked();
    return new ImportAccountCandidateResponse(
        account.getId(),
        account.getName(),
        placement.accountType(),
        account.getNativeCurrency(),
        account.getPortfolio().getId(),
        account.getPortfolio().getName(),
        identifierMasked == null ? account.getContractIdentifierMasked() : identifierMasked,
        reason);
  }

  // A card statement belongs on a card, any other statement on an account without a statement
  // cycle (a holdings statement is matched on positions above).
  static Predicate<Account> ofTheStatementsKind(ImportTemplateDefinition definition) {
    boolean card =
        ImportAccountProposalService.ACCOUNT_CREDIT_CARD.equals(
            ImportAccountProposalService.accountTypeOf(definition));
    return account -> account.isHasStatementCycle() == card;
  }

  // Several cards can share a masked number's visible digits: such a match is never certain.
  private static boolean masked(@Nullable ImportFileAccountIdentifier identifier) {
    return identifier != null
        && ImportTemplateValues.IDENTIFIER_MASKED_CARD.equals(identifier.kind());
  }
}
