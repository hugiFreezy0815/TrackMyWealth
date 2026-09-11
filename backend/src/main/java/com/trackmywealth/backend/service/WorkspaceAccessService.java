package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared guard for every workspace-scoped write service (first extracted from {@code
 * InstitutionService} and {@code AccountService}, which had each independently reimplemented the
 * same check). {@code actorWorkspaceId} is {@code null} for a {@code SYSTEM_ADMINISTRATOR} with no
 * linked {@code workspace_member} - a normal state (see {@code AuthenticatedUserPrincipal}'s
 * Javadoc) that such a caller nonetheless cannot create workspace-scoped data in. Centralizing this
 * keeps the rejection wording and reasoning from drifting as more workspace-scoped write services
 * are added.
 */
@Service
public class WorkspaceAccessService {

  private final WorkspaceRepository workspaceRepository;

  public WorkspaceAccessService(WorkspaceRepository workspaceRepository) {
    this.workspaceRepository = workspaceRepository;
  }

  /**
   * @param resourceDescription how to name what's being created in the rejection message, e.g.
   *     {@code "an account"} or {@code "an institution"}.
   * @return a reference to the caller's own workspace (not a fetch: RLS's tenant_isolation_read
   *     policy already confines a real SELECT to {@code app.current_workspace_id}, which {@code
   *     JwtAuthenticationFilter} set to this exact id when it authenticated the current request).
   */
  public Workspace requireWorkspace(UUID actorWorkspaceId, String resourceDescription) {
    if (actorWorkspaceId == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Caller has no workspace of their own to create " + resourceDescription + " in.");
    }
    return workspaceRepository.getReferenceById(actorWorkspaceId);
  }
}
