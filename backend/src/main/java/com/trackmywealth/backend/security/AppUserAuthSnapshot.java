package com.trackmywealth.backend.security;

import java.util.UUID;

/**
 * Exactly what {@link JwtAuthenticationFilter} needs to decide whether a request authenticates,
 * fetched in one query (see {@code AppUserRepository.findAuthSnapshot}) rather than navigating
 * {@code AppUser.householdMember.household} lazily on a since-detached entity outside any
 * transaction. {@code householdId} is {@code null} when there is no linked {@code household_member}
 * at all. {@code sessionStatus} is the {@code user_session} row named by the presented token's
 * {@code sessionId} claim - {@code null} if that session no longer exists at all (US-02-03: a
 * revoked session isn't deleted, so in practice this is either {@code "ACTIVE"} or {@code
 * "REVOKED"}, but the join is a plain {@code LEFT JOIN} so a missing row degrades safely to "not
 * authenticated" rather than a query failure).
 */
public record AppUserAuthSnapshot(
    UUID userId,
    String role,
    String status,
    int tokenVersion,
    UUID householdId,
    String sessionStatus) {}
