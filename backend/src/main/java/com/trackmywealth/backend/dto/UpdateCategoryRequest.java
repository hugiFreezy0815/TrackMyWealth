package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code PUT /api/v1/categories/{id}} (US-08-04): full replacement of the mutable
 * attributes, so {@code parentId = null} moves a workspace category to the top level. A shipped
 * default keeps its shipped position: for a default, {@code parentId} may be omitted (or sent as
 * its current parent), and any other parent is a 422. Activation has its own endpoints because
 * deactivation cascades.
 *
 * <p>{@code version} is the {@link CategoryResponse#version()} the client last read; if the
 * category has changed since, the update is a 409 and the client should reload it.
 */
public record UpdateCategoryRequest(
    UUID parentId,
    @NotBlank @Size(max = CategoryLabels.MAX_LENGTH) String nameEn,
    @NotBlank @Size(max = CategoryLabels.MAX_LENGTH) String nameDe,
    @NotNull Integer version) {

  public UpdateCategoryRequest {
    nameEn = CategoryLabels.normalize(nameEn);
    nameDe = CategoryLabels.normalize(nameDe);
  }
}
