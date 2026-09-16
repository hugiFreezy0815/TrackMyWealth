package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/sharing-grants} (US-03-03). Exactly one of {@code
 * scopeAccountId}/{@code scopeInstitutionId} must be set, matching {@code scopeType} - validated in
 * {@code SharingGrantService}, not here, since it's a cross-field rule (the same "validate before
 * insert" ordering {@code AccountService}/{@code AccountOwnershipService} already use), mirroring
 * V6's own {@code CHECK} constraint on {@code sharing_grant}.
 *
 * <p>The granter ({@code grantedByMemberId}) is never a request field - it's always the
 * authenticated caller's own {@code workspace_member}, resolved server-side, so a caller can never
 * grant access "as" someone else.
 */
public record CreateSharingGrantRequest(
    @NotNull UUID grantedToMemberId,
    @NotBlank @Pattern(regexp = ScopeTypeValues.PATTERN) String scopeType,
    UUID scopeAccountId,
    UUID scopeInstitutionId,
    @NotBlank @Pattern(regexp = AccessLevelValues.PATTERN) String accessLevel) {}
