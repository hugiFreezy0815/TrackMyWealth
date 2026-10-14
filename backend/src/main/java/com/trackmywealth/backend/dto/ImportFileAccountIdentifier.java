package com.trackmywealth.backend.dto;

/**
 * US-07-10: the account identifier an import file names, as its template's rule reads it: {@code
 * kind} ({@code IBAN} or {@code MASKED_CARD}) and {@code value}, normalized, or {@code null} when
 * the file names none the rule can read, or names several different ones. The value is matched
 * against the accounts' keyed hashes and then dropped: never log or store it ({@link #toString}
 * leaves it out).
 */
public record ImportFileAccountIdentifier(String kind, String value) {

  public boolean found() {
    return value != null;
  }

  @Override
  public String toString() {
    return "ImportFileAccountIdentifier[kind=" + kind + ", found=" + found() + "]";
  }
}
