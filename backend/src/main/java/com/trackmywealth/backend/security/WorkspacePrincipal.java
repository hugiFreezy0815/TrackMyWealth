package com.trackmywealth.backend.security;

import java.util.UUID;

/**
 * The contract an authenticated principal (the value returned by {@code
 * Authentication.getPrincipal()}) must satisfy for {@link
 * com.trackmywealth.backend.config.WorkspaceContextTransactionExecutionListener} to resolve a
 * workspace context for the current transaction.
 *
 * <p>No production authentication mechanism populates this yet - EPIC-02's login/JWT filter chain
 * (US-02-02) is what will construct the real {@code Authentication} carrying an instance of this
 * per-request, once it exists. Until then, no request reaches this interface at all (everything
 * except {@code /api/v1/setup/**} and the actuator endpoints requires authentication that doesn't
 * exist yet, so it is simply denied - see {@code SecurityConfig}).
 *
 * <p>Deliberately a single {@code workspaceId}, not a set of accessible workspaces plus an "acting
 * as" selector: membership is the only access grant (FR-TEN-008) and a user is a member of one
 * workspace (FR-HOU-010), so there is never a second workspace to choose between.
 */
@FunctionalInterface
public interface WorkspacePrincipal {

  UUID workspaceId();
}
