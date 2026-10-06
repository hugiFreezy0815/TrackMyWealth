package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportBatchCountsResponse;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportParseResult;
import com.trackmywealth.backend.dto.ImportRateNeed;
import com.trackmywealth.backend.dto.ImportRowErrorResponse;
import com.trackmywealth.backend.dto.ImportRowResponse;
import com.trackmywealth.backend.dto.ImportSameFileResponse;
import com.trackmywealth.backend.dto.ImportTemplateCandidateResponse;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.dto.ResolvedImportTemplate;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ImportFile;
import com.trackmywealth.backend.entity.ImportRowRaw;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import com.trackmywealth.backend.repository.ImportBatchRepository;
import com.trackmywealth.backend.repository.ImportFileRepository;
import com.trackmywealth.backend.repository.ImportRowRawRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import jakarta.persistence.EntityManager;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/**
 * US-07-04: a member uploads a bank file for one of their accounts, previews what its import would
 * do, adjusts it and commits it ({@link ImportCommitService}) or discards it. Nothing reaches the
 * ledger before the commit.
 *
 * <p>Every endpoint needs {@code EDIT} access to the account - an import is a write, and its rows
 * are raw bank data - so a {@code BALANCE_ONLY} or {@code READ} grant, or another workspace's
 * account, is the audited 404. A batch id outside the account is the same 404.
 *
 * <p>A file is read by the parser outside any database transaction (as template detection and dry
 * runs are, #267 review): a PDF statement can take seconds and must not hold a pooled connection.
 * The batch and its rows are then written in a transaction of their own, which checks the account,
 * the batch's status and its version again. The exchange rates the rows need are looked up between
 * the two, outside any transaction too: an old date can make the FX provider answer first (#223).
 *
 * <p>An account holds at most {@code app.import.max-open-batches-per-account} batches that are
 * neither committed nor discarded (409 {@code IMPORT_TOO_MANY_OPEN_BATCHES}): each keeps its file
 * of up to 5 MB in the database until then.
 */
@Service
public class ImportBatchService {

  static final String VERSIONED_RESOURCE = "import batch";
  private static final String BATCH_ENTITY_TYPE = "ImportBatch";
  private static final String MESSAGE_PREFIX = "tmw.import.row.";
  private static final String ACTIVE = "ACTIVE";
  private static final String UPLOAD_LOCK_PREFIX = "import-upload:";
  private static final int MAX_PAGE_SIZE = 200;
  private static final int MAX_FILE_NAME_LENGTH = 255;
  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("uploadedAt"), Sort.Order.desc("id"));

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final ImportTemplateService importTemplateService;
  private final ImportFileParserService parser;
  private final ImportStagingService stagingService;
  private final ImportRowCheckService rowCheckService;
  private final ImportBatchRepository batchRepository;
  private final ImportFileRepository fileRepository;
  private final ImportRowRawRepository rowRepository;
  private final VersionPreconditionService versionPreconditionService;
  private final WorkspaceRepository workspaceRepository;
  private final MessageSource messageSource;
  private final ObjectMapper objectMapper;
  private final EntityManager entityManager;
  private final TransactionTemplate readOnlyTransaction;
  private final TransactionTemplate writeTransaction;
  private final int maxOpenBatches;

  public ImportBatchService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      ImportTemplateService importTemplateService,
      ImportFileParserService parser,
      ImportStagingService stagingService,
      ImportRowCheckService rowCheckService,
      ImportBatchRepository batchRepository,
      ImportFileRepository fileRepository,
      ImportRowRawRepository rowRepository,
      VersionPreconditionService versionPreconditionService,
      WorkspaceRepository workspaceRepository,
      MessageSource messageSource,
      ObjectMapper objectMapper,
      EntityManager entityManager,
      PlatformTransactionManager transactionManager,
      @Value("${app.import.max-open-batches-per-account}") int maxOpenBatches) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.importTemplateService = importTemplateService;
    this.parser = parser;
    this.stagingService = stagingService;
    this.rowCheckService = rowCheckService;
    this.batchRepository = batchRepository;
    this.fileRepository = fileRepository;
    this.rowRepository = rowRepository;
    this.versionPreconditionService = versionPreconditionService;
    this.workspaceRepository = workspaceRepository;
    this.messageSource = messageSource;
    this.objectMapper = objectMapper;
    this.entityManager = entityManager;
    this.readOnlyTransaction = new TransactionTemplate(transactionManager);
    this.readOnlyTransaction.setReadOnly(true);
    this.writeTransaction = new TransactionTemplate(transactionManager);
    this.maxOpenBatches = maxOpenBatches;
  }

  /**
   * Stores {@code content} as a new batch of the account. With {@code templateId}, or when exactly
   * one template's header fingerprint matches the file (FR-IMP-022), the file is parsed at once and
   * the batch is {@code PARSED}; otherwise it stays {@code UPLOADED} and the answer lists the
   * templates that can read it, for a {@link #parse}.
   *
   * @throws ImportFileRejectedException 422 for an empty file, or one the chosen template cannot
   *     read; nothing is stored then
   * @throws ApiException 409 {@code IMPORT_TOO_MANY_OPEN_BATCHES} when the account holds as many
   *     open batches as it may
   */
  public ImportBatchResponse upload(
      UUID accountId,
      String fileName,
      String mediaType,
      byte[] content,
      UUID templateId,
      AuthenticatedUserPrincipal actor) {
    String accountCurrency =
        readOnlyTransaction.execute(
            status -> {
              Account account = requireImportableAccount(accountId, actor);
              requireRoomForAnotherBatch(account);
              return account.getNativeCurrency();
            });
    if (content.length == 0) {
      throw new ImportFileRejectedException(ApiErrorCode.IMPORT_FILE_EMPTY, "The file is empty.");
    }
    List<ImportTemplateCandidateResponse> candidates = List.of();
    UUID chosen = templateId;
    if (chosen == null) {
      candidates = importTemplateService.detect(content, actor);
      chosen = singleExactMatch(candidates);
    }
    ResolvedImportTemplate template =
        chosen == null ? null : importTemplateService.resolveForImport(chosen, actor);
    ImportParseResult parsed =
        template == null ? null : parser.parse(content, template.definition(), accountCurrency);
    List<ImportTemplateCandidateResponse> offered = template == null ? candidates : List.of();
    Map<String, Boolean> rates =
        parsed == null ? new HashMap<>() : lookUpRates(accountId, parsed.rows(), actor);

    return writeTransaction.execute(
        status -> {
          Account account = requireImportableAccount(accountId, actor);
          // Uploads into one account queue here, so two at once cannot both take the last place.
          workspaceRepository.lockAdvisory(UPLOAD_LOCK_PREFIX + account.getId());
          requireRoomForAnotherBatch(account);
          ImportBatch batch = new ImportBatch();
          batch.setWorkspaceId(account.getWorkspace().getId());
          batch.setAccountId(account.getId());
          batch.setSourceFileName(safeFileName(fileName));
          batch.setSourceFileStorageRef(ImportBatchValues.STORAGE_DATABASE);
          String sha256 = sha256(content);
          batch.setFileSha256(sha256);
          batch.setUploadedBy(actor.userId());
          batchRepository.saveAndFlush(batch);

          ImportFile file = new ImportFile();
          file.setImportBatchId(batch.getId());
          file.setWorkspaceId(batch.getWorkspaceId());
          file.setContent(content);
          file.setSha256(sha256);
          file.setMediaType(mediaType);
          fileRepository.saveAndFlush(file);

          if (parsed != null) {
            useTemplate(batch, template);
            stagingService.stage(batch, account, parsed.rows(), rates);
          }
          return summary(batch, account, offered);
        });
  }

  /**
   * Parses the batch's stored file (again) with the current version of {@code templateId},
   * replacing its rows; allowed while it is {@code UPLOADED} or {@code PARSED}.
   */
  public ImportBatchResponse parse(
      UUID accountId,
      UUID batchId,
      UUID templateId,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    String accountCurrency =
        readOnlyTransaction.execute(
            status -> {
              Account account = requireImportableAccount(accountId, actor);
              requireParseable(requireBatch(account, batchId, actor), expectedVersion);
              return account.getNativeCurrency();
            });
    // Every parseable batch has its file: only a discard (no longer parseable) deletes it.
    byte[] content =
        readOnlyTransaction.execute(
            status ->
                fileRepository
                    .findById(batchId)
                    .map(ImportFile::getContent)
                    .orElseThrow(() -> new IllegalStateException("Batch has no stored file.")));
    ResolvedImportTemplate template = importTemplateService.resolveForImport(templateId, actor);
    ImportParseResult parsed = parser.parse(content, template.definition(), accountCurrency);
    Map<String, Boolean> rates = lookUpRates(accountId, parsed.rows(), actor);

    return writeTransaction.execute(
        status -> {
          Account account = requireImportableAccount(accountId, actor);
          ImportBatch batch = lockBatch(account, batchId, actor);
          // Checked again under the lock: another parse, a discard or a commit may have come first.
          requireParseable(batch, expectedVersion);
          useTemplate(batch, template);
          stagingService.stage(batch, account, parsed.rows(), rates);
          return summary(batch, account, List.of());
        });
  }

  /** The account's batches, newest first. */
  @Transactional(readOnly = true)
  public Page<ImportBatchResponse> list(
      UUID accountId, Pageable pageable, AuthenticatedUserPrincipal actor) {
    Account account = requireEditableAccount(accountId, actor);
    int size = Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE);
    Page<ImportBatch> page =
        batchRepository.findByAccountId(
            account.getId(), PageRequest.of(pageable.getPageNumber(), size, NEWEST_FIRST));
    Map<UUID, ImportBatchCountsResponse> counts = counts(page.getContent());
    Map<UUID, ImportSameFileResponse> sameFiles = sameFilesImportedIn(page.getContent());
    return page.map(batch -> summary(batch, account, List.of(), counts, sameFiles));
  }

  /** The batch's summary: status, template version, counts and the same-file warning. */
  @Transactional(readOnly = true)
  public ImportBatchResponse get(UUID accountId, UUID batchId, AuthenticatedUserPrincipal actor) {
    Account account = requireEditableAccount(accountId, actor);
    return summary(requireBatch(account, batchId, actor), account, List.of());
  }

  /**
   * The batch's rows in file order, optionally only those of one {@code status} ({@code PARSED},
   * {@code DUPLICATE}, {@code ERROR}); messages in the caller's language.
   */
  @Transactional(readOnly = true)
  public Page<ImportRowResponse> rows(
      UUID accountId,
      UUID batchId,
      String status,
      Pageable pageable,
      AuthenticatedUserPrincipal actor) {
    Account account = requireEditableAccount(accountId, actor);
    ImportBatch batch = requireBatch(account, batchId, actor);
    if (status != null && !ImportBatchValues.ROW_STATUSES.contains(status)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          ApiErrorCode.VALIDATION_FAILED,
          "status must be one of PARSED, DUPLICATE, ERROR.");
    }
    int size = Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE);
    PageRequest page = PageRequest.of(pageable.getPageNumber(), size);
    Page<ImportRowRaw> rows =
        status == null
            ? rowRepository.findByImportBatchIdOrderByRowNumber(batch.getId(), page)
            : rowRepository.findByImportBatchIdAndParseStatusOrderByRowNumber(
                batch.getId(), status, page);
    Locale locale = LocaleContextHolder.getLocale();
    return rows.map(row -> rowResponse(row, account, locale));
  }

  /**
   * Forces a {@code DUPLICATE} row into the commit, or keeps a {@code PARSED} row out of it. An
   * {@code ERROR} row cannot be included (422 {@code IMPORT_ROW_NOT_INCLUDABLE}). The batch's
   * version moves on, so a commit sent with the version read before this change is a 412.
   *
   * @return the batch's summary with its new counts and version
   */
  @Transactional
  public ImportBatchResponse setIncluded(
      UUID accountId,
      UUID batchId,
      int rowNumber,
      boolean included,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = requireImportableAccount(accountId, actor);
    ImportBatch batch = lockBatch(account, batchId, actor);
    requireStatus(batch, ImportBatchValues.PARSED);
    versionPreconditionService.requireCurrent(
        expectedVersion, batch.getVersion(), VERSIONED_RESOURCE);
    ImportRowRaw row = requireRow(batch, rowNumber);
    if (included && ImportBatchValues.ROW_ERROR.equals(row.getParseStatus())) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          ApiErrorCode.IMPORT_ROW_NOT_INCLUDABLE,
          "Row " + rowNumber + " could not be read and cannot be imported.");
    }
    if (row.isIncluded() != included) {
      row.setIncluded(included);
      rowRepository.saveAndFlush(row);
      // The rows are part of what a commit confirms: changing one is a change of the batch.
      batchRepository.touch(batch.getId());
      entityManager.refresh(batch);
    }
    return summary(batch, account, List.of());
  }

  /**
   * Discards a batch that is not committed: it becomes {@code DISCARDED}, its stored file is
   * deleted, and it can no longer be parsed or committed. Its rows stay, so a reconciliation can
   * still point at a booking it held (MISSING_TRANSACTION).
   */
  @Transactional
  public ImportBatchResponse discard(
      UUID accountId, UUID batchId, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    Account account = requireImportableAccount(accountId, actor);
    ImportBatch batch = lockBatch(account, batchId, actor);
    requireParseable(batch, expectedVersion);
    batch.setStatus(ImportBatchValues.DISCARDED);
    batchRepository.saveAndFlush(batch);
    fileRepository.deleteById(batch.getId());
    fileRepository.flush();
    return summary(batch, account, List.of());
  }

  // --- shared with ImportCommitService and ImportErrorExportService
  // -------------------------------

  /**
   * The account, if {@code actor} may import into it: {@code EDIT} access (else the audited 404),
   * an active account (409 {@code ACCOUNT_ARCHIVED}) that holds transactions (422).
   */
  Account requireImportableAccount(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = requireEditableAccount(accountId, actor);
    if (!ACTIVE.equals(account.getStatus())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          ApiErrorCode.ACCOUNT_ARCHIVED,
          "Cannot import into an archived account.");
    }
    if (!account.isHasTransactions()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "This account does not hold transactions.");
    }
    return account;
  }

  // Reads need the same access as writes (the rows are raw bank data), but an archived account's
  // imports stay readable.
  Account requireEditableAccount(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    return account;
  }

  ImportBatch requireBatch(Account account, UUID batchId, AuthenticatedUserPrincipal actor) {
    return batchRepository
        .findByIdAndAccountId(batchId, account.getId())
        .orElseThrow(() -> accessControlService.denyAsNotFound(actor, BATCH_ENTITY_TYPE, batchId));
  }

  ImportBatch lockBatch(Account account, UUID batchId, AuthenticatedUserPrincipal actor) {
    return batchRepository
        .findForUpdate(batchId, account.getId())
        .orElseThrow(() -> accessControlService.denyAsNotFound(actor, BATCH_ENTITY_TYPE, batchId));
  }

  /** 409 {@code IMPORT_BATCH_STATE} unless the batch is in {@code expected}. */
  static void requireStatus(ImportBatch batch, String expected) {
    if (!expected.equals(batch.getStatus())) {
      throw stateConflict(batch);
    }
  }

  static ApiException stateConflict(ImportBatch batch) {
    ApiException conflict =
        new ApiException(
            HttpStatus.CONFLICT,
            ApiErrorCode.IMPORT_BATCH_STATE,
            "This import batch is " + batch.getStatus() + "; that step is no longer possible.");
    conflict.getBody().setProperty("status", batch.getStatus());
    return conflict;
  }

  ImportBatchResponse summary(
      ImportBatch batch, Account account, List<ImportTemplateCandidateResponse> candidates) {
    List<ImportBatch> one = List.of(batch);
    return summary(batch, account, candidates, counts(one), sameFilesImportedIn(one));
  }

  private static ImportBatchResponse summary(
      ImportBatch batch,
      Account account,
      List<ImportTemplateCandidateResponse> candidates,
      Map<UUID, ImportBatchCountsResponse> counts,
      Map<UUID, ImportSameFileResponse> sameFiles) {
    return new ImportBatchResponse(
        batch.getId(),
        account.getId(),
        batch.getStatus(),
        batch.getSourceKind(),
        batch.getSourceFileName(),
        batch.getTemplateId(),
        batch.getTemplateVersionUsed(),
        counts.getOrDefault(batch.getId(), ImportBatchCountsResponse.NONE),
        sameFiles.get(batch.getId()),
        candidates,
        batch.getUploadedAt(),
        batch.getParsedAt(),
        batch.getCommittedAt(),
        VersionPreconditionService.persistedVersion(batch.getVersion(), VERSIONED_RESOURCE));
  }

  /** The canonical values a row was stored with, or {@code null} for a row that was unreadable. */
  CanonicalImportRow canonical(ImportRowRaw row) {
    return row.getCanonicalData() == null
        ? null
        : objectMapper.readValue(row.getCanonicalData(), CanonicalImportRow.class);
  }

  /** A JSON object of strings, in its stored order (raw cells, message arguments). */
  Map<String, String> stringMap(String json) {
    if (json == null) {
      return new LinkedHashMap<>();
    }
    JavaType type =
        objectMapper
            .getTypeFactory()
            .constructMapType(LinkedHashMap.class, String.class, String.class);
    return objectMapper.readValue(json, type);
  }

  /** The localized message of a row error or warning {@code code}, arguments in order. */
  ImportRowErrorResponse message(String code, Map<String, String> args, Locale locale) {
    return new ImportRowErrorResponse(
        code,
        args,
        messageSource.getMessage(MESSAGE_PREFIX + code, args.values().toArray(), code, locale));
  }

  // --- internals ---------------------------------------------------------------------------------

  private void requireParseable(ImportBatch batch, Integer expectedVersion) {
    if (!ImportBatchValues.PARSEABLE.contains(batch.getStatus())) {
      throw stateConflict(batch);
    }
    versionPreconditionService.requireCurrent(
        expectedVersion, batch.getVersion(), VERSIONED_RESOURCE);
  }

  // A row number of a batch the caller may see: nothing is enumerated by trying others, so a
  // missing one is a plain 404 (reviewed in ArchitectureTest).
  private ImportRowRaw requireRow(ImportBatch batch, int rowNumber) {
    return rowRepository
        .findByImportBatchIdAndRowNumber(batch.getId(), rowNumber)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "The import batch has no row " + rowNumber + "."));
  }

  private static void useTemplate(ImportBatch batch, ResolvedImportTemplate template) {
    batch.setTemplateId(template.id());
    batch.setTemplateVersionUsed(template.templateVersion());
    batch.setSourceKind(
        template.definition().isPdf()
            ? ImportBatchValues.SOURCE_DOCUMENT
            : ImportBatchValues.SOURCE_CSV);
  }

  // The row counts of every batch that has rows, in one query however many batches a page holds;
  // a batch without rows (UPLOADED) is absent.
  private Map<UUID, ImportBatchCountsResponse> counts(List<ImportBatch> batches) {
    List<UUID> parsed =
        batches.stream()
            .filter(batch -> !ImportBatchValues.UPLOADED.equals(batch.getStatus()))
            .map(ImportBatch::getId)
            .toList();
    Map<UUID, ImportBatchCountsResponse> counts = new HashMap<>();
    if (parsed.isEmpty()) {
      return counts;
    }
    for (Object[] row : rowRepository.countByBatches(parsed)) {
      counts.put(
          (UUID) row[0],
          new ImportBatchCountsResponse(
              count(row[1]),
              count(row[2]),
              count(row[3]),
              count(row[4]),
              count(row[5]),
              count(row[6]),
              count(row[7])));
    }
    return counts;
  }

  private static int count(Object value) {
    return ((Number) value).intValue();
  }

  // Per batch of one account, the latest other committed batch of the same file (the same-file
  // warning), in one query however many batches a page holds; a batch without one is absent.
  private Map<UUID, ImportSameFileResponse> sameFilesImportedIn(List<ImportBatch> batches) {
    Map<UUID, ImportSameFileResponse> sameFiles = new HashMap<>();
    List<String> hashes =
        batches.stream()
            .map(ImportBatch::getFileSha256)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (hashes.isEmpty()) {
      return sameFiles;
    }
    List<ImportBatch> committed =
        batchRepository.findByAccountIdAndStatusAndFileSha256InOrderByCommittedAtDesc(
            batches.get(0).getAccountId(), ImportBatchValues.COMMITTED, hashes);
    for (ImportBatch batch : batches) {
      committed.stream()
          .filter(earlier -> !earlier.getId().equals(batch.getId()))
          .filter(earlier -> earlier.getFileSha256().equals(batch.getFileSha256()))
          .findFirst()
          .ifPresent(
              earlier ->
                  sameFiles.put(
                      batch.getId(),
                      new ImportSameFileResponse(earlier.getId(), earlier.getCommittedAt())));
    }
    return sameFiles;
  }

  // The exchange rates the rows need, looked up outside any transaction: the provider can take
  // seconds for an old date (#223), which must not hold a connection's transaction or the batch's
  // lock. Only which rates are needed is read in a (short) transaction.
  private Map<String, Boolean> lookUpRates(
      UUID accountId, List<ParsedImportRow> rows, AuthenticatedUserPrincipal actor) {
    Set<ImportRateNeed> needed =
        readOnlyTransaction.execute(
            status ->
                rowCheckService.ratesNeeded(requireImportableAccount(accountId, actor), rows));
    return rowCheckService.lookUpRates(needed);
  }

  // Each open batch keeps its file in the database until it is committed or discarded.
  private void requireRoomForAnotherBatch(Account account) {
    long open =
        batchRepository.countByAccountIdAndStatusIn(account.getId(), ImportBatchValues.PARSEABLE);
    if (open >= maxOpenBatches) {
      ApiException full =
          new ApiException(
              HttpStatus.CONFLICT,
              ApiErrorCode.IMPORT_TOO_MANY_OPEN_BATCHES,
              "This account already has "
                  + maxOpenBatches
                  + " imports that are neither committed nor discarded; commit or discard one"
                  + " first.");
      full.getBody().setProperty("maxOpenBatches", maxOpenBatches);
      throw full;
    }
  }

  private ImportRowResponse rowResponse(ImportRowRaw row, Account account, Locale locale) {
    CanonicalImportRow canonical = canonical(row);
    ImportRowErrorResponse error =
        row.getErrorCode() == null
            ? null
            : message(row.getErrorCode(), stringMap(row.getErrorArgs()), locale);
    List<ImportRowErrorResponse> warnings = new ArrayList<>();
    for (String warning : row.getWarningCodes()) {
      warnings.add(
          message(
              warning,
              ImportRowCheckService.warningArgs(warning, canonical, account.getNativeCurrency()),
              locale));
    }
    return new ImportRowResponse(
        row.getRowNumber(),
        row.getParseStatus(),
        row.isIncluded(),
        stringMap(row.getRawData()),
        canonical,
        row.getDuplicateOfTransactionId(),
        error,
        warnings,
        row.getResultingTransactionId());
  }

  // Exactly one template whose header fingerprint is the file's: unambiguous enough to parse with.
  private static UUID singleExactMatch(List<ImportTemplateCandidateResponse> candidates) {
    List<ImportTemplateCandidateResponse> exact =
        candidates.stream()
            .filter(c -> ImportTemplateCandidateResponse.EXACT_HEADER.equals(c.match()))
            .toList();
    return exact.size() == 1 ? exact.get(0).template().id() : null;
  }

  /**
   * The name only, never a client's directory path (either separator), without control characters
   * (a NUL included), bounded like any other stored text; {@code null} when nothing is left. Plain
   * string handling: the name is client input, and {@code Path.of} throws on some of it.
   */
  static String safeFileName(String fileName) {
    if (fileName == null) {
      return null;
    }
    int lastSeparator = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
    String safe = CONTROL_CHARACTERS.matcher(fileName.substring(lastSeparator + 1)).replaceAll("");
    if (safe.isBlank()) {
      return null;
    }
    return safe.length() > MAX_FILE_NAME_LENGTH ? safe.substring(0, MAX_FILE_NAME_LENGTH) : safe;
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available.", e);
    }
  }
}
