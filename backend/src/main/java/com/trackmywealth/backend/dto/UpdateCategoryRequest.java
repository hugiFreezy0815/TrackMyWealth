package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code PUT /api/v1/categories/{id}} (US-08-04): full replacement of the mutable
 * attributes, so {@code parentId = null} moves a workspace category to the top level. A shipped
 * default keeps its shipped position: for a default, {@code parentId} may be omitted, null, or its
 * current parent, and any other parent is a 422. Activation has its own endpoints because
 * deactivation cascades.
 *
 * <p>The version the client last read travels in the mandatory {@code If-Match} header
 * (FR-CNC-001/002, ADR 0004), never in the body.
 */
public record UpdateCategoryRequest(
    @Nullable UUID parentId,
    @NotBlank @Size(max = CategoryLabels.MAX_LENGTH) String nameEn,
    @NotBlank @Size(max = CategoryLabels.MAX_LENGTH) String nameDe) {

  public UpdateCategoryRequest {
    nameEn = CategoryLabels.normalize(nameEn);
    nameDe = CategoryLabels.normalize(nameDe);
  }
}
