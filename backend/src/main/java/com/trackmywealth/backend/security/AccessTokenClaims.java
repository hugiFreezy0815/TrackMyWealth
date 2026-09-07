package com.trackmywealth.backend.security;

import java.util.UUID;

/**
 * The two claims {@link com.trackmywealth.backend.service.JwtService} embeds in every access token
 * and {@link JwtAuthenticationFilter} checks on every request. {@code tokenVersion} is what makes
 * revocation real (FR-AUT-005): incrementing {@code app_user.token_version} invalidates every
 * previously issued access token immediately, without waiting for JWT expiry, because the filter
 * compares this embedded value against the user's current one on every request.
 */
public record AccessTokenClaims(UUID userId, int tokenVersion) {}
