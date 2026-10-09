package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.NotBlankText;
import jakarta.validation.constraints.Size;

/**
 * Rolls back a committed import batch (US-07-05). {@code reason} is required whichever way the
 * rollback goes: it is recorded on the batch, and is the void reason of every row a void path
 * voids. {@link NotBlankText}, because the service strips it.
 */
public record ImportRollbackRequest(@NotBlankText @Size(max = 500) String reason) {}
