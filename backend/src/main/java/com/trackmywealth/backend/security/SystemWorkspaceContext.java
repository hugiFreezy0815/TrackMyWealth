package com.trackmywealth.backend.security;

import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Runs system work - a background job, never a request - inside one workspace (#223). {@code
 * WorkspaceContextTransactionExecutionListener} derives a transaction's {@code
 * app.current_workspace_id}, and with it every RLS policy, from the authenticated principal; a
 * Quartz thread has none, so this puts a principal on the thread that carries only the workspace
 * id: no user, no roles, nothing an access check could grant anything on. The thread's previous
 * security context is restored afterwards.
 *
 * <p>The workspace id must come from the system's own data (e.g. {@code
 * transfer_detection_fx_pending}), never from a caller.
 */
public final class SystemWorkspaceContext {

  private SystemWorkspaceContext() {}

  /** Runs {@code work} as the system inside {@code workspaceId}. */
  public static void runInWorkspace(UUID workspaceId, Runnable work) {
    SecurityContext previous = SecurityContextHolder.getContext();
    SecurityContext system = SecurityContextHolder.createEmptyContext();
    WorkspacePrincipal principal = () -> workspaceId;
    system.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    SecurityContextHolder.setContext(system);
    try {
      work.run();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}
