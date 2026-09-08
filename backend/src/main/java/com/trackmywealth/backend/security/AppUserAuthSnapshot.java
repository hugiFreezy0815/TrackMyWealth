package com.trackmywealth.backend.security;

import java.util.UUID;

/**
 * Exactly what {@link JwtAuthenticationFilter} needs to decide whether a request authenticates,
 * fetched in one query (see {@code AppUserRepository.findAuthSnapshot}) rather than navigating
 * {@code AppUser.householdMember.household} lazily on a since-detached entity outside any
 * transaction. {@code householdId} is {@code null} when there is no linked {@code household_member}
 * at all.
 */
public record AppUserAuthSnapshot(
    UUID userId, String role, String status, int tokenVersion, UUID householdId) {}
