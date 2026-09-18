package com.trackmywealth.backend.dto;

/**
 * Response body for {@code POST /api/v1/auth/login}. Exactly one of {@code mfaChallengeToken} or
 * {@code tokens} is non-null: when {@code mfaRequired} is {@code true} (US-02-04), {@code
 * mfaChallengeToken} is set and {@code tokens} is {@code null} - the client must then call {@code
 * POST /api/v1/auth/mfa/verify} with it and a valid TOTP code to actually receive {@code tokens}.
 * When {@code mfaRequired} is {@code false}, {@code tokens} is set directly and {@code
 * mfaChallengeToken} is {@code null}.
 */
public record LoginResponse(
    boolean mfaRequired, String mfaChallengeToken, AuthTokensResponse tokens) {}
