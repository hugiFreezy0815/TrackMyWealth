package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/users/me/mfa/disable} (US-02-04). {@code password} satisfies
 * the story's "recent re-authentication" (FR-AUT-012) precondition, same as {@link
 * MfaEnrollmentRequest}. {@code code} - a current authenticator code - is additionally required
 * while MFA is enabled (a stolen session plus a reused password must not be enough to remove the
 * second factor); it is omitted only to cancel a pending, never-confirmed enrollment.
 */
public record MfaDisableRequest(
    @NotBlank String password, @Pattern(regexp = "\\d{6}") String code) {}
