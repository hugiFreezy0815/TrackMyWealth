package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code PUT /api/v1/categories/{id}} (US-08-04): full replacement of the mutable
 * attributes, so {@code parentId = null} moves a category to the top level. A shipped default may
 * be relabelled but must be sent with its current parent, since defaults keep their shipped
 * position. Activation has its own endpoints because deactivation cascades.
 */
public record UpdateCategoryRequest(
    UUID parentId,
    @NotBlank @Size(max = CategoryLabels.MAX_LENGTH) String nameEn,
    @NotBlank @Size(max = CategoryLabels.MAX_LENGTH) String nameDe) {

  public UpdateCategoryRequest {
    nameEn = CategoryLabels.normalize(nameEn);
    nameDe = CategoryLabels.normalize(nameDe);
  }
}
