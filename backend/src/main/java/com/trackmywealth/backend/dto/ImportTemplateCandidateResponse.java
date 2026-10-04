package com.trackmywealth.backend.dto;

/**
 * A template that can read an uploaded file (FR-IMP-022), best first. {@code match} is {@link
 * #EXACT_HEADER} when the file's header fingerprint equals the template's, {@link
 * #MAPPED_COLUMNS_PRESENT} when the header merely holds every column the template maps. The member
 * can always pick another template.
 */
public record ImportTemplateCandidateResponse(ImportTemplateResponse template, String match) {

  public static final String EXACT_HEADER = "EXACT_HEADER";
  public static final String MAPPED_COLUMNS_PRESENT = "MAPPED_COLUMNS_PRESENT";
}
