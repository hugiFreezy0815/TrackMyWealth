package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * US-07-11: registers the identifier an import file names on an account, without the member typing
 * it again: the open batch {@code batchId} of account {@code importAccountId} holds the file. The
 * plain identifier is read on the server and never sent or returned.
 */
public record AccountIdentifierFromImportRequest(
    @Schema(description = "The account the import batch belongs to.") @NotNull UUID importAccountId,
    @Schema(description = "An open import batch (UPLOADED or PARSED) whose file names the account.")
        @NotNull
        UUID batchId) {}
