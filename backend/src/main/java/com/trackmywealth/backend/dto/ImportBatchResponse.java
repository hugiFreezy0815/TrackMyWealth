package com.trackmywealth.backend.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An import batch's summary (US-07-04). {@code templateId} and {@code templateVersion} are the
 * exact template version its rows were parsed with (FR-IMP-023); both are {@code null} while it is
 * {@code UPLOADED}. {@code templateCandidates} lists the templates that can read the file, best
 * first, only in the answer to an upload that could not pick one by itself; it is empty otherwise.
 * {@code sameFileImportedIn} is set when this exact file was committed to the account before.
 * {@code version} is the batch's {@code If-Match} revision (ADR 0004).
 */
public record ImportBatchResponse(
    UUID id,
    UUID accountId,
    String status,
    String sourceKind,
    String sourceFileName,
    UUID templateId,
    String templateVersion,
    ImportBatchCountsResponse counts,
    ImportSameFileResponse sameFileImportedIn,
    List<ImportTemplateCandidateResponse> templateCandidates,
    OffsetDateTime uploadedAt,
    OffsetDateTime parsedAt,
    OffsetDateTime committedAt,
    int version) {

  public ImportBatchResponse {
    templateCandidates = List.copyOf(templateCandidates);
  }
}
