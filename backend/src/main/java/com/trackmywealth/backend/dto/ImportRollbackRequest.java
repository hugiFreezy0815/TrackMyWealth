package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Rolls back a committed import batch (US-07-05). {@code reason} is required whichever way the
 * rollback goes: it is recorded on the batch, and is the void reason of every row a void path
 * voids.
 */
public record ImportRollbackRequest(@NotBlank @Size(max = 500) String reason) {}
