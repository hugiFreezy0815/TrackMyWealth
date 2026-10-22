package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.UpdateWorkspaceCurrencyRequest;
import com.trackmywealth.backend.dto.UpdateWorkspaceNameRequest;
import com.trackmywealth.backend.dto.WorkspaceResponse;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.FinancialAuditLogRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.validation.CurrencyCodes;
import com.trackmywealth.backend.validation.WorkspaceNames;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-06-05/US-03-07 workspace settings. The legacy dependent-member guard is shared by currency
 * and name updates until #389 removes dependents. System administrators must not rename a
 * workspace, regardless of an old membership link; #398 removes those links altogether.
 */
@Service
public class WorkspaceService {

  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceService.class);
  private static final String VERSIONED_RESOURCE = "workspace";

  private final WorkspaceRepository workspaceRepository;
  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final AccessControlService accessControlService;
  private final VersionPreconditionService versionPreconditionService;
  private final FinancialAuditLogRepository financialAuditLogRepository;

  public WorkspaceService(
      WorkspaceRepository workspaceRepository,
      WorkspaceMemberRepository workspaceMemberRepository,
      AccessControlService accessControlService,
      VersionPreconditionService versionPreconditionService,
      FinancialAuditLogRepository financialAuditLogRepository) {
    this.workspaceRepository = workspaceRepository;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.accessControlService = accessControlService;
    this.versionPreconditionService = versionPreconditionService;
    this.financialAuditLogRepository = financialAuditLogRepository;
  }

  @Transactional(readOnly = true)
  public WorkspaceResponse getCurrent(AuthenticatedUserPrincipal actor) {
    requireWorkspaceSettingsMember(actor);
    return toResponse(findCurrent(actor));
  }

  /**
   * US-03-07: any active member can name their own workspace. The row lock serializes concurrent
   * writes; the audit record is inserted in the same transaction so it cannot outlive a rollback.
   */
  @Transactional
  public WorkspaceResponse updateName(
      UpdateWorkspaceNameRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    requireWorkspaceSettingsMember(actor);

    UUID workspaceId = actor.workspaceId();
    Workspace workspace =
        workspaceRepository
            .findByIdForUpdate(workspaceId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Authenticated member's workspace no longer exists: " + workspaceId));
    versionPreconditionService.requireCurrent(
        expectedVersion, workspace.getVersion(), VERSIONED_RESOURCE);

    String oldName = workspace.getName();
    String newName = WorkspaceNames.normalize(request.name());
    if (Objects.equals(oldName, newName)) {
      return toResponse(workspace);
    }

    workspace.setName(newName);
    workspace = workspaceRepository.saveAndFlush(workspace);

    financialAuditLogRepository.recordWorkspaceNameChange(
        workspaceId, actor.userId(), oldName, newName);

    return toResponse(workspace);
  }

  @Transactional
  public WorkspaceResponse updateCurrency(
      UpdateWorkspaceCurrencyRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    UUID memberId = requireWorkspaceSettingsMember(actor);

    UUID workspaceId = actor.workspaceId();
    Workspace workspace =
        workspaceRepository
            .findByIdForUpdate(workspaceId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Authenticated member's workspace no longer exists: " + workspaceId));
    versionPreconditionService.requireCurrent(
        expectedVersion, workspace.getVersion(), VERSIONED_RESOURCE);

    String newCurrency = CurrencyCodes.requireMonetary(request.currency(), "currency");
    String oldCurrency = workspace.getCurrency();
    workspace.setCurrency(newCurrency);
    workspace = workspaceRepository.saveAndFlush(workspace);

    LOG.info(
        "Workspace currency changed workspaceId={} memberId={} from={} to={}",
        workspaceId,
        memberId,
        oldCurrency,
        newCurrency);
    return toResponse(workspace);
  }

  private UUID requireWorkspaceSettingsMember(AuthenticatedUserPrincipal actor) {
    // In the updated model administrators are never business members (FR-USR-013/015).
    // Retain this guard while the old setup flow still creates their membership (#398).
    if ("SYSTEM_ADMINISTRATOR".equals(actor.role())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found.");
    }
    UUID memberId = accessControlService.requireActingMember(actor);
    WorkspaceMember member =
        workspaceMemberRepository
            .findById(memberId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Authenticated workspace member no longer exists: " + memberId));
    if (member.isDependent()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "A dependent member cannot change workspace settings.");
    }
    return memberId;
  }

  private Workspace findCurrent(AuthenticatedUserPrincipal actor) {
    UUID workspaceId = actor.workspaceId();
    return workspaceRepository
        .findById(workspaceId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Authenticated member's workspace no longer exists: " + workspaceId));
  }

  private static WorkspaceResponse toResponse(Workspace workspace) {
    return new WorkspaceResponse(
        workspace.getId(),
        workspace.getName(),
        workspace.getCurrency(),
        VersionPreconditionService.persistedVersion(workspace.getVersion(), VERSIONED_RESOURCE));
  }
}
