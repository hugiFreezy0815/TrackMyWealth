package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code PATCH /api/v1/admin/users/{id}} (US-02-01). Partial update: a {@code
 * null} field means "leave unchanged" - {@code @Email}/{@code @Pattern} only validate a field that
 * is actually provided, per standard Jakarta Validation null-safety. An empty string is not
 * rejected by {@code @Email} itself (Hibernate Validator treats "" as a valid email), so {@link
 * com.trackmywealth.backend.service.AdminUserService#editUser} explicitly rejects a
 * non-null-but-blank email rather than silently blanking the target's address. Demoting the
 * target's role away from {@code SYSTEM_ADMINISTRATOR} is subject to the same
 * last-active-administrator guard as disable (FR-USR-005).
 */
public record EditUserRequest(
    @Email String email,
    @Pattern(regexp = "SYSTEM_ADMINISTRATOR|STANDARD_USER") String role,
    @Pattern(regexp = "EN|DE") String language) {}
