package com.trackmywealth.backend.dto;

/**
 * Shared EN/DE label rules for the category request DTOs.
 *
 * <p>Keep {@link #MAX_LENGTH} in sync with V46's category/workspace-override CHECK constraints so
 * every database writer and the HTTP validation boundary enforce the same contract (#171).
 */
final class CategoryLabels {

  static final int MAX_LENGTH = 100;

  private CategoryLabels() {}

  static String normalize(String label) {
    String value = RequestStrings.blankToNull(label);
    return value == null ? null : value.strip();
  }
}
