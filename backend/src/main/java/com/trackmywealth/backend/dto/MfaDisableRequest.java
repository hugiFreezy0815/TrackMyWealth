package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/users/me/mfa/disable} (US-02-04). {@code password} satisfies
 * the story's "recent re-authentication" (FR-AUT-012) precondition, same as {@link
 * MfaEnrollmentRequest}.
 */
public record MfaDisableRequest(@NotBlank String password) {}
