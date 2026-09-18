package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/users/me/mfa/enroll} (US-02-04). {@code password} satisfies
 * the story's "recent re-authentication" (FR-AUT-012) precondition by being re-verified inline,
 * rather than requiring a separate step-up-auth session.
 */
public record MfaEnrollmentRequest(@NotBlank String password) {}
