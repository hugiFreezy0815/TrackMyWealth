package com.trackmywealth.backend.security;

import java.util.UUID;

/**
 * The three claims {@link com.trackmywealth.backend.service.JwtService} embeds in every access
 * token and {@link JwtAuthenticationFilter} checks on every request. {@code tokenVersion} is what
 * makes user-wide revocation real (FR-AUT-005): incrementing {@code app_user.token_version}
 * invalidates every previously issued access token immediately, without waiting for JWT expiry,
 * because the filter compares this embedded value against the user's current one on every request.
 * {@code sessionId} does the same at a single-session granularity (US-02-03): revoking one {@code
 * user_session} must not affect a user's other active sessions, which a user-wide counter alone
 * cannot express - the filter additionally checks that this specific session is still {@code
 * ACTIVE}.
 */
public record AccessTokenClaims(UUID userId, int tokenVersion, UUID sessionId) {}
