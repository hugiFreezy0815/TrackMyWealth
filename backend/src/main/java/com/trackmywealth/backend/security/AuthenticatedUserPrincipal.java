package com.trackmywealth.backend.security;

import java.util.UUID;

/**
 * The real {@code Authentication.getPrincipal()} value {@link JwtAuthenticationFilter} constructs
 * for every successfully-authenticated request - the first production implementation of {@link
 * HouseholdPrincipal}. {@code householdId} is {@code null} for a {@code SYSTEM_ADMINISTRATOR} with
 * no linked {@code household_member} (US-02-05: administration rights confer no financial-data
 * access), which {@link
 * com.trackmywealth.backend.config.HouseholdContextTransactionExecutionListener} already handles
 * correctly - a {@code null} household id leaves {@code app.current_household_id} unset, and every
 * RLS policy then denies by default.
 */
public record AuthenticatedUserPrincipal(UUID userId, String role, UUID householdId)
    implements HouseholdPrincipal {}
