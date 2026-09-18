package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/users/me/mfa/confirm} (US-02-04) - the code the
 * authenticator app generated from the secret a just-preceding {@code POST
 * /api/v1/users/me/mfa/enroll} returned.
 */
public record MfaConfirmRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {}
