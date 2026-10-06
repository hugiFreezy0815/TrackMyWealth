package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportErrorExportResponse;
import com.trackmywealth.backend.dto.ImportParseRequest;
import com.trackmywealth.backend.dto.ImportRowInclusionRequest;
import com.trackmywealth.backend.dto.ImportRowResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.ImportBatchService;
import com.trackmywealth.backend.service.ImportCommitService;
import com.trackmywealth.backend.service.ImportErrorExportService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.RetryableWhenBusy;
import com.trackmywealth.backend.web.VersionedResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
 * commit or discard it. Rules in {@link ImportBatchService} and {@link ImportCommitService}. Every
 * endpoint needs {@code EDIT} access to the account; the batch's {@code version} is its {@code
 * If-Match} revision (ADR 0004).
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/imports")
public class ImportBatchController {

  private static final String BY_ID = "/{batchId}";

  private final ImportBatchService importBatchService;
  private final ImportCommitService importCommitService;
  private final ImportErrorExportService importErrorExportService;

  public ImportBatchController(
      ImportBatchService importBatchService,
      ImportCommitService importCommitService,
      ImportErrorExportService importErrorExportService) {
    this.importBatchService = importBatchService;
    this.importCommitService = importCommitService;
    this.importErrorExportService = importErrorExportService;
  }

  /**
   * Stores the file (at most 5 MB, else 413 {@code IMPORT_FILE_TOO_LARGE}) as a new batch. With
   * {@code templateId}, or when exactly one template's header fingerprint matches, it is parsed at
   * once ({@code PARSED}); otherwise it stays {@code UPLOADED} and {@code templateCandidates} lists
   * the templates that can read it. A 422 for an empty file, one the template cannot read, or more
   * than 20,000 rows ({@code IMPORT_FILE_TOO_MANY_ROWS}).
   */
  @RetryableWhenBusy
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ImportBatchResponse> upload(
      @PathVariable UUID accountId,
      @RequestPart("file") MultipartFile file,
      @RequestParam(required = false) UUID templateId,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportBatchResponse created =
        importBatchService.upload(
            accountId,
            file.getOriginalFilename(),
            file.getContentType(),
            bytesOf(file),
            templateId,
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

  /** Parses the stored file (again) with a template, replacing the batch's rows. */
  @RetryableWhenBusy
  @PostMapping(BY_ID + "/parse")
  public ResponseEntity<ImportBatchResponse> parse(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody ImportParseRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportBatchResponse batch =
        importBatchService.parse(
            accountId, batchId, request.templateId(), IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(batch, batch.version());
  }

  /** The account's batches, newest first. */
  @GetMapping
  public Page<ImportBatchResponse> list(
      @PathVariable UUID accountId,
      Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return importBatchService.list(accountId, pageable, actor);
  }

  @GetMapping(BY_ID)
  public ResponseEntity<ImportBatchResponse> get(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportBatchResponse batch = importBatchService.get(accountId, batchId, actor);
    return VersionedResponse.ok(batch, batch.version());
  }

  /**
   * The preview's rows in file order; {@code status} narrows them to PARSED, DUPLICATE or ERROR.
   */
  @GetMapping(BY_ID + "/rows")
  public Page<ImportRowResponse> rows(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @RequestParam(required = false) String status,
      Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return importBatchService.rows(accountId, batchId, status, pageable, actor);
  }

  /**
   * Forces a duplicate into the commit or keeps a new row out of it. {@code If-Match} is the
   * batch's version; the answer is the batch with its new counts and version.
   */
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

  /** Ends a batch that is not committed and deletes its stored file. */
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

  private static byte[] bytesOf(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read the uploaded file.", e);
    }
  }
}
