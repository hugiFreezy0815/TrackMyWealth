package com.trackmywealth.backend.dto;

/**
 * Response body for {@code POST /api/v1/auth/login}. {@code tokens} is {@code null} exactly when
 * {@code mfaRequired} is {@code true} - US-02-04 (out of this sprint, see the architect decision on
 * US-02-02) fills in the TOTP-verification step that this shape already leaves room for; every user
 * created in this sprint has {@code mfa_enabled = false}, so {@code mfaRequired} never actually
 * turns {@code true} yet.
 */
public record LoginResponse(boolean mfaRequired, AuthTokensResponse tokens) {}
