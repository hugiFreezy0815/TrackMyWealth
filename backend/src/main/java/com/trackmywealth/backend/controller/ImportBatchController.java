package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportErrorExportResponse;
import com.trackmywealth.backend.dto.ImportParseRequest;
import com.trackmywealth.backend.dto.ImportRollbackRequest;
import com.trackmywealth.backend.dto.ImportRollbackResponse;
import com.trackmywealth.backend.dto.ImportRowInclusionRequest;
import com.trackmywealth.backend.dto.ImportRowResponse;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.ImportBatchService;
import com.trackmywealth.backend.service.ImportCommitService;
import com.trackmywealth.backend.service.ImportErrorExportService;
import com.trackmywealth.backend.service.ImportRollbackService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.Paged.SortField;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.SuccessStatus;
import com.trackmywealth.backend.web.VersionedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * US-07-04: upload a bank file for one account, preview what its import would do, adjust it, and
 * commit or discard it; US-07-05: roll a committed one back. Rules in {@link ImportBatchService},
 * {@link ImportCommitService} and {@link ImportRollbackService}. Every endpoint needs the caller to
 * be a member of the account's workspace; the batch's {@code version} is its {@code If-Match}
 * revision (ADR 0004).
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/imports")
@Tag(name = "Imports")
public class ImportBatchController {

  private static final String BY_ID = "/{batchId}";

  private final ImportBatchService importBatchService;
  private final ImportCommitService importCommitService;
  private final ImportErrorExportService importErrorExportService;
  private final ImportRollbackService importRollbackService;

  public ImportBatchController(
      ImportBatchService importBatchService,
      ImportCommitService importCommitService,
      ImportErrorExportService importErrorExportService,
      ImportRollbackService importRollbackService) {
    this.importBatchService = importBatchService;
    this.importCommitService = importCommitService;
    this.importErrorExportService = importErrorExportService;
    this.importRollbackService = importRollbackService;
  }

  /**
   * Stores the file (at most 5 MB, else 413 {@code IMPORT_FILE_TOO_LARGE}) as a new batch. With
   * {@code templateId}, or when exactly one template's header fingerprint matches, it is parsed at
   * once ({@code PARSED}); otherwise it stays {@code UPLOADED} and {@code templateCandidates} lists
   * the templates that can read it. An XML file or ZIP archive without {@code templateId} is read
   * as an ISO 20022 CAMT statement ({@code camt.053}, {@code camt.052}; #350) and parsed at once. A
   * 422 for an empty file, one the template cannot read, or more than 20,000 rows ({@code
   * IMPORT_FILE_TOO_MANY_ROWS}); for a CAMT file also {@code IMPORT_CAMT_UNSUPPORTED}, {@code
   * IMPORT_CAMT_LIMIT_EXCEEDED} and {@code IMPORT_CAMT_MULTIPLE_ACCOUNTS}.
   */
  @SuccessStatus(HttpStatus.CREATED)
  @Operation(operationId = "createImportBatch", summary = "Upload a statement file for import")
  @ProblemCodes({
    ProblemCode.ACCOUNT_ARCHIVED,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.IMPORT_TEMPLATE_INVALID,
    ProblemCode.IMPORT_TEMPLATE_UNSUPPORTED,
    ProblemCode.IMPORT_TEMPLATE_MISMATCH,
    ProblemCode.IMPORT_ENCODING_SUSPECT,
    ProblemCode.IMPORT_FILE_EMPTY,
    ProblemCode.IMPORT_FILE_NO_DATA_ROWS,
    ProblemCode.IMPORT_FILE_MALFORMED,
    ProblemCode.IMPORT_FILE_TOO_MANY_ROWS,
    ProblemCode.IMPORT_CAMT_UNSUPPORTED,
    ProblemCode.IMPORT_CAMT_LIMIT_EXCEEDED,
    ProblemCode.IMPORT_CAMT_MULTIPLE_ACCOUNTS,
    ProblemCode.IMPORT_FILE_TOO_MANY_PAGES,
    ProblemCode.IMPORT_PDF_LIMIT_EXCEEDED,
    ProblemCode.IMPORT_PDF_NO_TEXT,
    ProblemCode.IMPORT_PDF_BUSY,
    ProblemCode.IMPORT_SPREADSHEET_LIMIT_EXCEEDED,
    ProblemCode.IMPORT_SPREADSHEET_BUSY,
    ProblemCode.IMPORT_OCR_FAILED,
    ProblemCode.IMPORT_OCR_UNAVAILABLE,
    ProblemCode.IMPORT_TOO_MANY_OPEN_BATCHES,
    ProblemCode.ACCOUNT_IDENTIFICATION_UNAVAILABLE
  })
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ImportBatchResponse> upload(
      @PathVariable UUID accountId,
      @RequestPart("file") MultipartFile file,
      @RequestParam(required = false) UUID templateId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate snapshotDate,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportBatchResponse created =
        importBatchService.upload(
            accountId,
            file.getOriginalFilename(),
            file.getContentType(),
            bytesOf(file),
            templateId,
            snapshotDate,
            actor);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path(BY_ID)
            .buildAndExpand(created.id())
            .toUri();
    return ResponseEntity.created(location)
        .eTag(IfMatchVersionParser.toEtag(created.version()))
        .body(created);
  }

  /**
   * Parses the stored file (again) with a template, replacing the batch's rows; a CAMT statement
   * without one (#350). Without {@code templateId}, any other file is a 400.
   */
  @Operation(
      operationId = "parseImportBatch",
      summary = "Read an uploaded file into rows to preview")
  @ProblemCodes({
    ProblemCode.ACCOUNT_ARCHIVED,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.IMPORT_TEMPLATE_INVALID,
    ProblemCode.IMPORT_TEMPLATE_UNSUPPORTED,
    ProblemCode.IMPORT_TEMPLATE_MISMATCH,
    ProblemCode.IMPORT_ENCODING_SUSPECT,
    ProblemCode.IMPORT_FILE_EMPTY,
    ProblemCode.IMPORT_FILE_NO_DATA_ROWS,
    ProblemCode.IMPORT_FILE_MALFORMED,
    ProblemCode.IMPORT_FILE_TOO_MANY_ROWS,
    ProblemCode.IMPORT_CAMT_UNSUPPORTED,
    ProblemCode.IMPORT_CAMT_LIMIT_EXCEEDED,
    ProblemCode.IMPORT_CAMT_MULTIPLE_ACCOUNTS,
    ProblemCode.IMPORT_FILE_TOO_MANY_PAGES,
    ProblemCode.IMPORT_PDF_LIMIT_EXCEEDED,
    ProblemCode.IMPORT_PDF_NO_TEXT,
    ProblemCode.IMPORT_PDF_BUSY,
    ProblemCode.IMPORT_SPREADSHEET_LIMIT_EXCEEDED,
    ProblemCode.IMPORT_SPREADSHEET_BUSY,
    ProblemCode.IMPORT_OCR_FAILED,
    ProblemCode.IMPORT_OCR_UNAVAILABLE,
    ProblemCode.IMPORT_BATCH_STATE,
    ProblemCode.ACCOUNT_IDENTIFICATION_UNAVAILABLE
  })
  @PostMapping(BY_ID + "/parse")
  public ResponseEntity<ImportBatchResponse> parse(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody ImportParseRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportBatchResponse batch =
        importBatchService.parse(
            accountId,
            batchId,
            request.templateId(),
            request.snapshotDate(),
            IfMatchVersionParser.parse(ifMatch),
            actor);
    return VersionedResponse.ok(batch, batch.version());
  }

  /** The account's batches, newest upload first unless {@code sort} says otherwise. */
  @Operation(operationId = "listImportBatches", summary = "List an account's imports")
  @GetMapping
  public PageResponse<ImportBatchResponse> list(
      @PathVariable UUID accountId,
      @Paged(sort = @SortField(name = "uploadedAt"), defaultSort = "uploadedAt,desc")
          Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return PageResponse.of(importBatchService.list(accountId, pageable, actor));
  }

  @Operation(operationId = "getImportBatch", summary = "Read an import")
  @GetMapping(BY_ID)
  public ResponseEntity<ImportBatchResponse> get(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportBatchResponse batch = importBatchService.get(accountId, batchId, actor);
    return VersionedResponse.ok(batch, batch.version());
  }

  /**
   * The preview's rows in file order; {@code status} narrows them to PARSED, DUPLICATE, ERROR or
   * SKIPPED. While the batch is PARSED, each row shows what the commit will do with it now (#308).
   */
  @Operation(operationId = "listImportBatchRows", summary = "List an import's preview rows")
  @GetMapping(BY_ID + "/rows")
  public PageResponse<ImportRowResponse> rows(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @RequestParam(required = false) String status,
      @Paged(
              sort = @SortField(name = "rowNumber"),
              defaultSort = "rowNumber,asc",
              tieBreaker = "rowNumber")
          Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return PageResponse.of(importBatchService.rows(accountId, batchId, status, pageable, actor));
  }

  /**
   * Forces a duplicate into the commit or keeps a new row out of it. {@code If-Match} is the
   * batch's version; the answer is the batch with its new counts and version.
   */
  @Operation(operationId = "setImportRowIncluded", summary = "Include or leave out one preview row")
  @ProblemCodes({
    ProblemCode.ACCOUNT_ARCHIVED,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.IMPORT_BATCH_STATE,
    ProblemCode.IMPORT_ROW_NOT_INCLUDABLE
  })
  @PatchMapping(BY_ID + "/rows/{rowNumber}")
  public ResponseEntity<ImportBatchResponse> setIncluded(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @PathVariable int rowNumber,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody ImportRowInclusionRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportBatchResponse batch =
        importBatchService.setIncluded(
            accountId,
            batchId,
            rowNumber,
            request.included(),
            IfMatchVersionParser.parse(ifMatch),
            actor);
    return VersionedResponse.ok(batch, batch.version());
  }

  /**
   * FR-IMP-012: the rows that could not be imported, as CSV in the source file's delimiter and
   * encoding - their cells, then {@code error_code} and {@code error_message}.
   */
  @Operation(
      operationId = "getImportBatchErrors",
      summary = "Download an import's row errors as CSV",
      responses =
          @ApiResponse(
              responseCode = "200",
              description = "The rows that could not be imported, as CSV",
              content =
                  @Content(
                      mediaType = "text/csv",
                      schema = @Schema(type = "string", format = "binary"))))
  @GetMapping(BY_ID + "/errors.csv")
  public ResponseEntity<byte[]> errors(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportErrorExportResponse export = importErrorExportService.export(accountId, batchId, actor);
    Charset charset = Charset.forName(export.encoding());
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", charset))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(export.fileName()).build().toString())
        .body(export.csv().getBytes(charset));
  }

  /** Inserts the included rows into the ledger; {@code PARSED -> COMMITTED}. */
  @Operation(operationId = "commitImportBatch", summary = "Book an import's rows")
  @ProblemCodes({
    ProblemCode.CONFLICT,
    ProblemCode.ACCOUNT_ARCHIVED,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.IMPORT_BATCH_STATE,
    ProblemCode.IMPORT_ACCOUNT_UNCONFIRMED,
    ProblemCode.IMPORT_SNAPSHOT_ALREADY_RECORDED,
    ProblemCode.IMPORT_SNAPSHOT_CONFLICT,
    ProblemCode.IMPORT_SNAPSHOT_RECONCILIATION_DECIDED,
    ProblemCode.IMPORT_HOLDINGS_UNRESOLVED,
    ProblemCode.IMPORT_HOLDINGS_UNREADABLE
  })
  @PostMapping(BY_ID + "/commit")
  public ResponseEntity<ImportBatchResponse> commit(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportBatchResponse batch =
        importCommitService.commit(accountId, batchId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(batch, batch.version());
  }

  /**
   * US-07-10: confirms importing the batch into this account although its file does not name the
   * account for certain ({@code accountIdentification}); until then the commit is a 409 {@code
   * IMPORT_ACCOUNT_UNCONFIRMED}.
   */
  @Operation(
      operationId = "confirmImportBatchAccount",
      summary = "Confirm the account an import belongs to")
  @ProblemCodes({
    ProblemCode.ACCOUNT_ARCHIVED,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.IMPORT_BATCH_STATE
  })
  @PostMapping(BY_ID + "/confirm-account")
  public ResponseEntity<ImportBatchResponse> confirmAccount(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportBatchResponse batch =
        importBatchService.confirmAccount(
            accountId, batchId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(batch, batch.version());
  }

  /** Ends a batch that is not committed and deletes its stored file. */
  @Operation(operationId = "discardImportBatch", summary = "Discard an import that was not booked")
  @ProblemCodes({
    ProblemCode.ACCOUNT_ARCHIVED,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.IMPORT_BATCH_STATE
  })
  @PostMapping(BY_ID + "/discard")
  public ResponseEntity<ImportBatchResponse> discard(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportBatchResponse batch =
        importBatchService.discard(accountId, batchId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(batch, batch.version());
  }

  /**
   * Rolls a committed batch back as a whole ({@code COMMITTED -> ROLLED_BACK} or {@code VOIDED}):
   * its transactions are deleted while nobody has worked on them, and voided once someone has - the
   * answer names those and why. {@code reason} (required, at most 500 characters) is recorded on
   * the batch and becomes the void reason. The statement balance snapshot its commit recorded is
   * deleted, or withdrawn while an accepted reconciliation's adjusting entry points at it; while a
   * member's decision on that reconciliation stands, the rollback is a 409 {@code
   * IMPORT_ROLLBACK_RECONCILIATION_DECIDED} (US-07-12). The {@code ETag} is the batch's new
   * version.
   */
  @Operation(operationId = "rollbackImportBatch", summary = "Undo a booked import")
  @ProblemCodes({
    ProblemCode.ACCOUNT_ARCHIVED,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.IMPORT_BATCH_STATE,
    ProblemCode.IMPORT_ROLLBACK_RECONCILIATION_DECIDED
  })
  @PostMapping(BY_ID + "/rollback")
  public ResponseEntity<ImportRollbackResponse> rollback(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody ImportRollbackRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportRollbackResponse rollback =
        importRollbackService.rollback(
            accountId, batchId, request.reason(), IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(rollback, rollback.batch().version());
  }

  private static byte[] bytesOf(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read the uploaded file.", e);
    }
  }
}
