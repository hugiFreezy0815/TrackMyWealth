package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/auth/mfa/verify} (US-02-04) - step 2 of login for an
 * MFA-enabled user. {@code challengeToken} is the value {@link LoginResponse#mfaChallengeToken()}
 * returned from step 1.
 */
public record MfaVerifyRequest(
    @NotBlank String challengeToken, @NotBlank @Pattern(regexp = "\\d{6}") String code) {}
