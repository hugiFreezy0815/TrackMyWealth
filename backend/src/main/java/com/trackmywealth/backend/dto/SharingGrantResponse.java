package com.trackmywealth.backend.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SharingGrantResponse(
    UUID id,
    UUID workspaceId,
    UUID grantedToMemberId,
    String scopeType,
    UUID scopeAccountId,
    UUID scopeInstitutionId,
    String accessLevel,
    UUID grantedByMemberId,
    OffsetDateTime grantedAt,
    OffsetDateTime revokedAt) {}
