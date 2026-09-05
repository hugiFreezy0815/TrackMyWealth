package com.trackmywealth.backend.dto;

/**
 * The token pair returned by any endpoint that logs a user in - the setup flow's auto-login
 * (US-01-03) today, {@code POST /login} (US-02-02) once it exists. {@code refreshToken} is returned
 * in plaintext exactly once; only its hash is ever persisted (FR-AUT-004).
 */
public record AuthTokensResponse(
    String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {}
