package com.trackmywealth.backend.security;

import java.util.UUID;

/**
 * Exactly what {@link JwtAuthenticationFilter} needs to decide whether a request authenticates,
 * fetched in one query (see {@code AppUserRepository.findAuthSnapshot}) rather than navigating
 * {@code AppUser.workspaceMember.workspace} lazily on a since-detached entity outside any
 * transaction. {@code workspaceId} is {@code null} when there is no linked {@code workspace_member}
 * at all. {@code sessionStatus} is the {@code user_session} row named by the presented token's
 * {@code sessionId} claim - {@code null} if that session no longer exists at all (US-02-03: a
 * revoked session isn't deleted, so in practice this is either {@code "ACTIVE"} or {@code
 * "REVOKED"}, but the join is a plain {@code LEFT JOIN} so a missing row degrades safely to "not
 * authenticated" rather than a query failure).
 *
 * <p>{@code workspaceMemberStatus} (US-04-01) is {@code null} exactly when {@code workspaceId} is -
 * no linked {@code workspace_member} at all, a normal state for a {@code SYSTEM_ADMINISTRATOR} (see
 * {@link AuthenticatedUserPrincipal}'s own Javadoc) that must still be allowed to authenticate.
 * When a membership does exist, {@link JwtAuthenticationFilter} requires it to be {@code "ACTIVE"}
 * - this is the one central place that check happens, rather than every workspace-scoped service
 * re-querying it individually (no code path deactivates a member yet, but US-03-04 will).
 */
public record AppUserAuthSnapshot(
    UUID userId,
    String role,
    String status,
    int tokenVersion,
    UUID workspaceId,
    String workspaceMemberStatus,
    String sessionStatus) {}
