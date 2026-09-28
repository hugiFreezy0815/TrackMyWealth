package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/categories} (US-08-04). {@code parentId} is optional (a
 * top-level category) and may name a shipped default. There is no {@code code}: the server
 * generates the stable code (FR-CAT-008) so it can never clash with a default one.
 */
public record CreateCategoryRequest(
    UUID parentId,
    @NotBlank @Size(max = CategoryLabels.MAX_LENGTH) String nameEn,
    @NotBlank @Size(max = CategoryLabels.MAX_LENGTH) String nameDe) {

  public CreateCategoryRequest {
    nameEn = CategoryLabels.normalize(nameEn);
    nameDe = CategoryLabels.normalize(nameDe);
  }
}
