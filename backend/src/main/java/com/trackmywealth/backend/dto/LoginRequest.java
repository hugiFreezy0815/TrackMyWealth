package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/auth/login} (US-02-02). */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
