package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.IdempotencyClaim;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportRoutingResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.IdempotencyService;
import com.trackmywealth.backend.service.ImportRoutingService;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.ResponseHeader;
import com.trackmywealth.backend.web.SendsHeaders;
import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * US-07-34 (#365): upload a statement without choosing its account first. The server works out the
 * file's format and, when the file names it for certain, its account (rules in {@link
 * ImportRoutingService}). Needs a workspace member; a batch is only ever created on an account the
 * member may import into.
 */
@RestController
@Tag(name = "Imports")
public class ImportRoutingController {

  private static final String UPLOAD_OPERATION = "createImport";

  private final ImportRoutingService importRoutingService;
  private final IdempotencyService idempotencyService;

  public ImportRoutingController(
      ImportRoutingService importRoutingService, IdempotencyService idempotencyService) {
    this.importRoutingService = importRoutingService;
    this.idempotencyService = idempotencyService;
  }

  /**
   * 201 {@code BATCH_CREATED} when the file names one account for certain (its IBAN or contract
   * number): the preview batch is created there, as {@code POST
   * /api/v1/accounts/{accountId}/imports} creates it, and {@code Location} is its URL. Otherwise
   * 200 and nothing is stored: {@code CHOOSE_ACCOUNT} lists the accounts the file may belong to,
   * {@code NO_ACCOUNT} has none (and the proposal for a new account when the file tells enough),
   * {@code NO_TEMPLATE} lists the templates that can read the file - upload again with one's {@code
   * templateId}. The member then uploads into the chosen account. Same limits and 422s as the
   * account upload.
   *
   * <p>With an {@code Idempotency-Key} (ADR 0006), a retry of an upload that created a batch
   * answers that 201 again and creates no second batch; an upload that stored nothing keeps no key,
   * so its retry is routed afresh.
   */
  @SuccessStatus({HttpStatus.CREATED, HttpStatus.OK})
  @Operation(
      operationId = "createImport",
      summary = "Upload a statement file without choosing its account")
  @ProblemCodes({
    ProblemCode.NOT_FOUND,
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
    ProblemCode.ACCOUNT_IDENTIFICATION_UNAVAILABLE,
    ProblemCode.IDEMPOTENCY_KEY_IN_USE,
    ProblemCode.IDEMPOTENCY_KEY_MISMATCH
  })
  @PostMapping(path = "/api/v1/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @SendsHeaders(ResponseHeader.LOCATION)
  public ResponseEntity<ImportRoutingResponse> upload(
      @RequestPart("file") MultipartFile file,
      @RequestParam(required = false) UUID templateId,
      @Parameter(
              description =
                  "A fresh UUID for each upload, the same UUID on every retry of it (ADR 0006)."
                      + " A retry within 24 hours of an upload that created a batch answers that"
                      + " 201 again and creates no second batch.",
              schema = @Schema(type = "string", format = "uuid"))
          @RequestHeader(value = IdempotencyService.HEADER, required = false)
          String idempotencyKey,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    byte[] content = bytesOf(file);
    String fileName = file.getOriginalFilename();
    String mediaType = file.getContentType();
    // An administrator without a workspace has no key scope; the routing refuses them.
    IdempotencyClaim claim =
        idempotencyKey == null || actor.workspaceId() == null
            ? null
            : idempotencyService.claim(
                UPLOAD_OPERATION,
                IdempotencyService.normalizedKey(idempotencyKey),
                IdempotencyService.fingerprint(content, templateId, fileName, mediaType),
                actor);
    ImportRoutingResponse routed;
    if (claim != null && claim.isReplay()) {
      routed =
          ImportRoutingResponse.created(
              idempotencyService.replayed(claim, ImportBatchResponse.class));
    } else {
      try {
        routed = importRoutingService.route(fileName, mediaType, content, templateId, actor, claim);
      } catch (RuntimeException failure) {
        if (claim != null) {
          releaseAfterFailure(claim, failure);
        }
        throw failure;
      }
      // Nothing stored: a retry is routed afresh, so the key is not kept.
      if (claim != null && routed.batch() == null) {
        idempotencyService.release(claim);
      }
    }
    ImportBatchResponse batch = routed.batch();
    if (batch == null) {
      return ResponseEntity.ok(routed);
    }
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/v1/accounts/{accountId}/imports/{batchId}")
            .buildAndExpand(batch.accountId(), batch.id())
            .toUri();
    return ResponseEntity.created(location).body(routed);
  }

  // A release that fails too must not hide why the upload failed (as the account upload does).
  private void releaseAfterFailure(IdempotencyClaim claim, RuntimeException failure) {
    try {
      idempotencyService.release(claim);
    } catch (RuntimeException releaseFailure) {
      failure.addSuppressed(releaseFailure);
    }
  }

  private static byte[] bytesOf(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read the uploaded file.", e);
    }
  }
}
