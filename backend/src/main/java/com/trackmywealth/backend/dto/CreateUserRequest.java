package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/admin/users} (US-02-01). No email/invite infrastructure
 * exists in this project, and it targets self-hosted, single-workspace use - the administrator sets
 * the new user's initial password directly, the same way the setup flow's own bootstrap
 * administrator gets one.
 */
public record CreateUserRequest(
    @NotBlank @Email String email,
    // FR-AUT-007: minimum-length-led policy, not composition rules - same minimum as
    // SetupAdministratorRequest, for the same reasoning.
    @NotBlank @Size(min = 12) String password,
    @NotBlank @Pattern(regexp = "SYSTEM_ADMINISTRATOR|STANDARD_USER") String role,
    @NotBlank @Pattern(regexp = "EN|DE") String language) {}
