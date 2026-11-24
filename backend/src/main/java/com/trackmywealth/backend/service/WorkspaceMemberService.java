package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.PagingValues;
import com.trackmywealth.backend.dto.WorkspaceMemberListItemResponse;
import com.trackmywealth.backend.dto.WorkspaceMemberSummaryResponse;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-03-04/FR-HHL-015: a workspace must always retain at least one active member, by analogy with
 * {@link AdminUserService}'s FR-USR-005 "last active administrator" guard, which this mirrors.
 * US-03-05: {@link #listWorkspaceMembers} lists the members of the caller's own workspace.
 *
 * <p>Self-service only: {@link #deactivateMember} rejects any target other than the caller's own
 * {@code workspace_member} row. Members never add or remove each other; assigning and removing a
 * membership is the administrator's job (FR-USR-014).
 */
@Service
public class WorkspaceMemberService {

  private static final String ACTIVE = "ACTIVE";
  private static final String INACTIVE = "INACTIVE";
  private static final String VERSIONED_RESOURCE = "workspace member";
  private static final String WORKSPACE_MEMBER_ENTITY_TYPE = "WorkspaceMember";

  // FR-HHL-015: a workspace must always retain at least this many active members - deactivating
  // one is only rejected when it would drop below this. Named for the same reason
  // AdminUserService.MINIMUM_ACTIVE_ADMINISTRATORS is (PMD's AvoidLiteralsInIfCondition), not just
  // style: it's the one number this entire guard exists to enforce.
  private static final long MINIMUM_ACTIVE_MEMBERS = 1;

  private final AppUserRepository appUserRepository;
  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final AuthorizationDenialAuditService authorizationDenialAuditService;
  private final VersionPreconditionService versionPreconditionService;
  private final AccessControlService accessControlService;

  public WorkspaceMemberService(
      AppUserRepository appUserRepository,
      WorkspaceMemberRepository workspaceMemberRepository,
      AuthorizationDenialAuditService authorizationDenialAuditService,
      VersionPreconditionService versionPreconditionService,
      AccessControlService accessControlService) {
    this.appUserRepository = appUserRepository;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.authorizationDenialAuditService = authorizationDenialAuditService;
    this.versionPreconditionService = versionPreconditionService;
    this.accessControlService = accessControlService;
  }

  /**
   * US-03-05: the members of the caller's own workspace, inactive ones included, ordered by display
   * name. Any member of the workspace may list them ({@link AccessControlService}); the workspace
   * comes from the caller's session, never from the request, and RLS confines the query to it as
   * well. Inactive memberships remain visible.
   */
  @Transactional(readOnly = true)
  public Page<WorkspaceMemberListItemResponse> listWorkspaceMembers(
      Pageable pageable, AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    PagingValues.requireBoundedFixedOrder(pageable);
    Page<WorkspaceMember> members =
        PagingValues.jpaPage(
            pageable,
            () -> workspaceMemberRepository.findMembersForListing(actor.workspaceId(), pageable),
            () -> workspaceMemberRepository.countMembersForListing(actor.workspaceId()));
    if (members.isEmpty()) {
      return new PageImpl<>(List.of(), pageable, members.getTotalElements());
    }

    // Exactly one login per member: V110 (#389) makes app_user.workspace_member_id unique and
    // refuses a member without a login, so toMap's duplicate-key failure and a missing login below
    // can only mean a corrupted database.
    Map<UUID, AppUser> usersByMemberId =
        appUserRepository
            .findUsersLinkedToMembers(members.stream().map(WorkspaceMember::getId).toList())
            .stream()
            .collect(
                Collectors.toMap(user -> user.getWorkspaceMember().getId(), Function.identity()));

    return members.map(member -> toListItem(member, usersByMemberId));
  }

  private static WorkspaceMemberListItemResponse toListItem(
      WorkspaceMember member, Map<UUID, AppUser> usersByMemberId) {
    AppUser user = usersByMemberId.get(member.getId());
    if (user == null) {
      throw new IllegalStateException(
          "Workspace member " + member.getId() + " has no login, which V110 forbids.");
    }
    return new WorkspaceMemberListItemResponse(
        member.getId(),
        user.getId(),
        member.getDisplayName(),
        ACTIVE.equals(member.getStatus()),
        VersionPreconditionService.persistedVersion(member.getVersion(), VERSIONED_RESOURCE));
  }

  @Transactional(readOnly = true)
  public WorkspaceMemberSummaryResponse getMember(
      UUID targetMemberId, AuthenticatedUserPrincipal actor) {
    UUID actingMemberId = accessControlService.requireActingMember(actor);
    requireSelf(targetMemberId, actingMemberId, actor.userId());
    WorkspaceMember target =
        workspaceMemberRepository
            .findById(targetMemberId)
            .orElseThrow(
                () ->
                    authorizationDenialAuditService.denyAsNotFound(
                        actor.userId(), WORKSPACE_MEMBER_ENTITY_TYPE, targetMemberId));
    return toSummary(target);
  }

  @Transactional
  public WorkspaceMemberSummaryResponse deactivateMember(
      UUID targetMemberId, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    UUID actingMemberId = accessControlService.requireActingMember(actor);
    requireSelf(targetMemberId, actingMemberId, actor.userId());

    // Locks the target plus every other currently-ACTIVE member of the same workspace in one
    // statement - see the repository query's own comment for why this must happen atomically
    // rather than as a separate count query.
    List<WorkspaceMember> lockedMembers =
        workspaceMemberRepository.lockTargetAndActiveMembersInWorkspace(
            targetMemberId, actor.workspaceId());
    WorkspaceMember target = extractTargetOrThrow(lockedMembers, targetMemberId, actor.userId());
    versionPreconditionService.requireCurrent(
        expectedVersion, target.getVersion(), VERSIONED_RESOURCE);

    // FR-STA-007 only lists ACTIVE -> INACTIVE as a valid transition out of ACTIVE; rejecting
    // "already not ACTIVE" here the same structured way AccountService.archiveAccount rejects an
    // out-of-table status transition, rather than silently no-op'ing.
    if (!ACTIVE.equals(target.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only an active member can be deactivated.");
    }

    long activeMemberCount = countActive(lockedMembers);
    if (activeMemberCount <= MINIMUM_ACTIVE_MEMBERS) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Cannot deactivate the last active member of a workspace (FR-HHL-015).");
    }

    target.setStatus(INACTIVE);
    target.setMemberUntil(LocalDate.now());
    target = workspaceMemberRepository.saveAndFlush(target);
    return toSummary(target);
  }

  // FR-TEN-006/US-28-02: the same audit-logged 404 every other single-resource authorization
  // denial in the app uses (see SessionService.revokeSession for the textually identical case) -
  // a caller who cannot act on this member must not be able to tell "not yours" from "does not
  // exist", and this exact probing pattern is what authorization_denial_log exists to catch.
  private void requireSelf(UUID targetMemberId, UUID actingMemberId, UUID actorUserId) {
    if (!actingMemberId.equals(targetMemberId)) {
      throw authorizationDenialAuditService.denyAsNotFound(
          actorUserId, WORKSPACE_MEMBER_ENTITY_TYPE, targetMemberId);
    }
  }

  // Same convention as requireSelf above - reachable today only if the acting member's own row is
  // deleted between requireActingMember and this lock query (targetMemberId == actingMemberId by
  // this point, so a miss here means the row briefly disappeared from under the caller, not that
  // someone else's id was guessed) - still a caller-supplied id failing to resolve, so still
  // belongs on the same audit trail.
  private WorkspaceMember extractTargetOrThrow(
      List<WorkspaceMember> lockedRows, UUID targetId, UUID actorUserId) {
    return lockedRows.stream()
        .filter(member -> member.getId().equals(targetId))
        .findFirst()
        .orElseThrow(
            () ->
                authorizationDenialAuditService.denyAsNotFound(
                    actorUserId, WORKSPACE_MEMBER_ENTITY_TYPE, targetId));
  }

  private long countActive(List<WorkspaceMember> members) {
    return members.stream().filter(member -> ACTIVE.equals(member.getStatus())).count();
  }

  private WorkspaceMemberSummaryResponse toSummary(WorkspaceMember member) {
    return new WorkspaceMemberSummaryResponse(
        member.getId(),
        member.getDisplayName(),
        member.getStatus(),
        member.getMemberSince(),
        member.getMemberUntil(),
        VersionPreconditionService.persistedVersion(member.getVersion(), VERSIONED_RESOURCE));
  }
}
