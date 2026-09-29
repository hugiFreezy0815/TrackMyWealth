package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code PUT /api/v1/accounts/{accountId}/transactions/{id}/category} (US-08-02):
 * the category a member chooses for one transaction, kept against every automatic run until the
 * member resets it ({@code DELETE} on the same path).
 */
public record SetTransactionCategoryRequest(@NotNull UUID categoryId) {}
