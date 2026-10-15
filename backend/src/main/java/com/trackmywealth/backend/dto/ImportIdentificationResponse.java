package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * US-07-10: what an import file says about its account, for a template that identifies accounts.
 * {@code outcome} is one of {@code MATCHED}, {@code MATCHED_MASKED}, {@code OTHER_ACCOUNT}, {@code
 * NO_MATCH}, {@code NOT_FOUND} and {@code UNAVAILABLE} ({@link ImportBatchValues}). {@code
 * proposedAccountId} is the account the file names, for {@code OTHER_ACCOUNT} and only when the
 * viewer may import into it. Unless the outcome is {@code MATCHED}, the batch is committed only
 * once the member has confirmed it ({@code confirmed}).
 */
public record ImportIdentificationResponse(
    String outcome, UUID proposedAccountId, boolean confirmationRequired, boolean confirmed) {}
