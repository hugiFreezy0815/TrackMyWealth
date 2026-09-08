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
 * as" selector: US-28-01's second acceptance criterion (a member resolving a shared grant from
 * another workspace) is an explicitly deferred sub-decision, out of scope until EPIC-03's {@code
 * sharing_grant} stories exist. This interface will need to grow a way to express that choice at
 * that point - see the story's own tracking note on GitHub issue #37.
 */
@FunctionalInterface
public interface WorkspacePrincipal {

  UUID workspaceId();
}
