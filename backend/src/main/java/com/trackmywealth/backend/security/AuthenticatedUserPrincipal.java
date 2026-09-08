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
 * RLS policy then denies by default. {@code sessionId} is the {@code user_session} the presented
 * access token belongs to (US-02-03) - what lets a caller list/revoke "my sessions" and know which
 * one is the one making the current request.
 */
public record AuthenticatedUserPrincipal(UUID userId, String role, UUID householdId, UUID sessionId)
    implements HouseholdPrincipal {}
