package com.trackmywealth.backend.dto;

/** Shared EN/DE label rules for the category request DTOs. */
final class CategoryLabels {

  static final int MAX_LENGTH = 100;

  private CategoryLabels() {}

  static String normalize(String label) {
    String value = RequestStrings.blankToNull(label);
    return value == null ? null : value.strip();
  }
}
