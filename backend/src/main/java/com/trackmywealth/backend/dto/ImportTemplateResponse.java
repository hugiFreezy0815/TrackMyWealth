package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One version of an import template (US-07-03). {@code id} names exactly this version, which is
 * what an import batch records; {@code familyId} is shared by every version of the template. Only
 * the {@code current} version can be changed: a change to a parse-relevant field returns a new
 * version with a new {@code id} and {@code templateVersion} (FR-IMP-023).
 *
 * <p>{@code systemProvided} templates ({@code workspaceId IS NULL}) are shipped and read-only;
 * {@code canEdit} is false for them. {@code version} is the concurrency token to send as a strong
 * {@code If-Match} ETag on every change (ADR 0004).
 */
public record ImportTemplateResponse(
    UUID id,
    UUID familyId,
    String name,
    UUID institutionCatalogueId,
    String templateClass,
    String templateVersion,
    LocalDate effectiveFrom,
    boolean current,
    boolean active,
    boolean systemProvided,
    boolean canEdit,
    String delimiter,
    String encoding,
    String decimalSeparator,
    String thousandsSeparator,
    String dateFormat,
    int headerRowIndex,
    int preambleRowCount,
    int trailingSummaryRowCount,
    String amountRepresentation,
    String currencyMode,
    String fixedCurrency,
    ImportColumnMapping columnMapping,
    Map<String, String> typeMapping,
    String accountIdentificationStrategy,
    List<String> headerColumns,
    String headerFingerprint,
    int version) {

  public ImportTemplateResponse {
    typeMapping = Collections.unmodifiableMap(new LinkedHashMap<>(typeMapping));
    if (headerColumns != null) {
      headerColumns = List.copyOf(headerColumns);
    }
  }
}
