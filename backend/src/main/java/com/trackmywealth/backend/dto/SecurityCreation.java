package com.trackmywealth.backend.dto;

import java.util.List;

/**
 * Result of {@code SecurityService#findOrCreate}: the record, whether this call created it, and -
 * when it did not - which of the caller's supplied values differ from the stored record and were
 * therefore ignored (shared reference data is never edited by a later caller, NFR-LIC-007).
 */
public record SecurityCreation(
    SecurityResponse security, boolean created, List<String> ignoredFields) {

  public SecurityCreation {
    // Defensive/immutable copy (SpotBugs EI_EXPOSE_REP), same as SecurityCompleteness.
    ignoredFields = List.copyOf(ignoredFields);
  }
}
