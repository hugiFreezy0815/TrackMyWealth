package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/auth/refresh} (US-02-02). */
public record RefreshTokenRequest(@NotBlank String refreshToken) {}
