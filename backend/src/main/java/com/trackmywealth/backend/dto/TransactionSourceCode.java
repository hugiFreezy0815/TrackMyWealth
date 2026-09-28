package com.trackmywealth.backend.dto;

/**
 * One classification code a transaction carries in {@code raw_source_data} (US-08-01), e.g. {@code
 * ("MCC", "5411")}: {@code standard} is a {@code category_source_mapping.source_standard} value.
 * Internal to {@code CategorizationService}: not part of any API response. It lives here rather
 * than as a nested type of the service because {@code ArchitectureTest} requires every class in
 * {@code ..service..} to be a {@code @Service}.
 */
public record TransactionSourceCode(String standard, String code) {

  /** The form a {@code SOURCE_CODE} rule's value takes, e.g. {@code MCC:5411}. */
  public String asRuleValue() {
    return standard + ":" + code;
  }
}
