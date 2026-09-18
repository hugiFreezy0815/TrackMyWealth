package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/accounts/{accountId}/reassign-institution} (US-04-04).
 *
 * <p>Moves an account to a different {@code financial_institution} container within the same
 * workspace - correcting a mismodelled provider, or reflecting a real-world institution merger -
 * without touching anything else about the account (C2: an account always belongs to exactly one
 * container, this is that container's replacement, not removal).
 */
public record ReassignAccountInstitutionRequest(@NotNull UUID financialInstitutionId) {}
