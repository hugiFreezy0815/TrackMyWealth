package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code PUT /api/v1/accounts/{accountId}/savings-pocket} (#325 follow-up).
 *
 * <p>Links an existing account of the workspace as the account's savings pocket, in place of the
 * one it has: the next import commits book the account's pocket moves there. The pocket must be an
 * active account in the account's currency that holds transactions, is no card or loan and has no
 * pocket of its own. A link cannot be removed, only replaced.
 */
public record LinkSavingsPocketRequest(@NotNull UUID savingsPocketAccountId) {}
