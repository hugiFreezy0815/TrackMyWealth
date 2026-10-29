package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.UpdateWorkspaceCurrencyRequest;
import com.trackmywealth.backend.dto.WorkspaceResponse;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.validation.CurrencyCodes;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-06-05 workspace settings. Any active member may change the workspace currency (US-03-06: every
 * member is a user with a login and has full access to the workspace).
 */
@Service
public class WorkspaceService {

  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceService.class);
  private static final String VERSIONED_RESOURCE = "workspace";

  private final WorkspaceRepository workspaceRepository;
  private final AccessControlService accessControlService;
  private final VersionPreconditionService versionPreconditionService;

  public WorkspaceService(
      WorkspaceRepository workspaceRepository,
      AccessControlService accessControlService,
      VersionPreconditionService versionPreconditionService) {
    this.workspaceRepository = workspaceRepository;
    this.accessControlService = accessControlService;
    this.versionPreconditionService = versionPreconditionService;
  }

  @Transactional(readOnly = true)
  public WorkspaceResponse getCurrent(AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    return toResponse(findCurrent(actor));
  }

  @Transactional
  public WorkspaceResponse updateCurrency(
      UpdateWorkspaceCurrencyRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    UUID memberId = accessControlService.requireActingMember(actor);

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
