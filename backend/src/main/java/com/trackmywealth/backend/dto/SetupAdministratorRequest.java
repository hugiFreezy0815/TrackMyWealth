package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/setup/administrator} (US-01-03). {@code currencyCode} sets
 * the auto-created "Personal Assets" container's currency (replacing V19's {@code CHF} placeholder)
 * and, as a starting default only, the new administrator's own reporting currency - the two remain
 * independently changeable afterward.
 */
public record SetupAdministratorRequest(
    @NotBlank @Email String email,
    // FR-AUT-007: minimum-length-led policy, not composition rules.
    @NotBlank @Size(min = 12) String password,
    @NotBlank String householdName,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currencyCode) {}
