package com.trackmywealth.backend.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Parses (again) an import batch's stored file with the current version of a template, or - for a
 * CAMT XML statement or ZIP archive of them, which no template reads (#350) - without {@code
 * templateId}. Any other file without one is a 400. {@code snapshotDate} dates a holdings statement
 * that states no date itself, such as a spreadsheet export (#273); it is refused for any other.
 */
public record ImportParseRequest(UUID templateId, LocalDate snapshotDate) {

  /** A cash statement's parse, or a holdings statement that states its own date. */
  public ImportParseRequest(UUID templateId) {
    this(templateId, null);
  }
}
