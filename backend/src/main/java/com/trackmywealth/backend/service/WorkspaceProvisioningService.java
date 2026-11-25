package com.trackmywealth.backend.service;

import com.trackmywealth.backend.entity.Portfolio;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.PortfolioRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import com.trackmywealth.backend.validation.WorkspaceNames;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Provisions a new tenant in the same transaction as its first login. */
@Service
public class WorkspaceProvisioningService {
  private final EntityManager entityManager;
  private final WorkspaceRepository workspaces;
  private final WorkspaceMemberRepository members;
  private final PortfolioRepository portfolios;

  public WorkspaceProvisioningService(
      EntityManager entityManager,
      WorkspaceRepository workspaces,
      WorkspaceMemberRepository members,
      PortfolioRepository portfolios) {
    this.entityManager = entityManager;
    this.workspaces = workspaces;
    this.members = members;
    this.portfolios = portfolios;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public WorkspaceMember create(String email, String workspaceName, String currency) {
    UUID workspaceId = UUID.randomUUID();
    // Set RLS context before inserting the workspace and its default portfolio.
    String previousContext =
        (String)
            entityManager
                .createNativeQuery(
                    "SELECT COALESCE(current_setting('app.current_workspace_id', true), '')")
                .getSingleResult();
    setWorkspaceContext(workspaceId.toString());
    WorkspaceMember member;
    try {
      member = provision(workspaceId, email, workspaceName, currency);
    } catch (RuntimeException e) {
      // A caller that catches the failure must not go on in the new tenant's RLS context. After a
      // failed write PostgreSQL has aborted the transaction and the restore fails too; the
      // original failure is the one to report.
      try {
        setWorkspaceContext(previousContext);
      } catch (RuntimeException restoreFailure) {
        e.addSuppressed(restoreFailure);
      }
      throw e;
    }
    setWorkspaceContext(previousContext);
    return member;
  }

  private WorkspaceMember provision(
      UUID workspaceId, String email, String workspaceName, String currency) {
    Workspace workspace = new Workspace();
    workspace.setId(workspaceId);
    workspace.setName(WorkspaceNames.normalize(workspaceName));
    workspace.setCurrency(currency);
    workspaces.saveAndFlush(workspace);
    Portfolio personalAssets =
        portfolios
            .findByWorkspaceIdAndPersonalAssetsDefaultTrue(workspaceId)
            .orElseThrow(
                () -> new IllegalStateException("Missing default Personal Assets portfolio."));
    personalAssets.setPortfolioCurrency(currency);
    // The caller links the login before commit. A failed write rolls back all three rows.
    return members.saveAndFlush(WorkspaceMember.newLoginMember(workspace, email));
  }

  private void setWorkspaceContext(String workspaceId) {
    entityManager
        .createNativeQuery("SELECT set_config('app.current_workspace_id', :workspaceId, true)")
        .setParameter("workspaceId", workspaceId)
        .getSingleResult();
  }
}
