package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.ImportAccountProposalResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.ImportAccountProposalService;
import com.trackmywealth.backend.web.RetryableWhenBusy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * US-07-11: a proposal for an account that does not exist yet, from a statement. Read-only: it
 * creates no account and stores no file; the member creates the account with {@code POST
 * /api/v1/accounts}. A file that is too large is refused before it gets here (the multipart limit);
 * an empty one is the parser's 422 {@code IMPORT_FILE_EMPTY}, as for a template dry run.
 */
@RestController
public class ImportAccountProposalController {

  private final ImportAccountProposalService proposals;

  public ImportAccountProposalController(ImportAccountProposalService proposals) {
    this.proposals = proposals;
  }

  /** The proposal for an uploaded file, read with a template version the member may see. */
  @RetryableWhenBusy
  @PostMapping(
      path = "/api/v1/import-templates/{templateId}/account-proposal",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ImportAccountProposalResponse propose(
      @PathVariable UUID templateId,
      @RequestPart("file") MultipartFile file,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return proposals.propose(templateId, bytesOf(file), actor);
  }

  /**
   * The proposal for an open import batch's file, typically one whose file names no account of the
   * workspace ({@code accountIdentification} {@code NO_MATCH}); needs access to its account.
   */
  @RetryableWhenBusy
  @PostMapping("/api/v1/accounts/{accountId}/imports/{batchId}/account-proposal")
  public ImportAccountProposalResponse proposeFromBatch(
      @PathVariable UUID accountId,
      @PathVariable UUID batchId,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return proposals.proposeFromBatch(accountId, batchId, actor);
  }

  private static byte[] bytesOf(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read the uploaded file.", e);
    }
  }
}
