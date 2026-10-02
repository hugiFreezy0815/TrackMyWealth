package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.WorkspaceMemberSummaryResponse;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-03-04/FR-HHL-015: a workspace must always retain at least one active member, by analogy with
 * {@link AdminUserService}'s FR-USR-005 "last active administrator" guard, which this mirrors.
 *
 * <p>Self-service only for now: {@link #deactivateMember} rejects any target other than the
 * caller's own {@code workspace_member} row. Whether a caller has {@code FULL} access to someone
 * else's membership belongs to {@code AccessControlService} (US-03-03), not reimplemented here.
 * Letting a {@code FULL}-access member deactivate another is a small follow-up: relax {@link
 * #requireSelf} through that service; nothing else about the guard below changes.
 */
@Service
public class WorkspaceMemberService {

  private static final String ACTIVE = "ACTIVE";
  private static final String INACTIVE = "INACTIVE";
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

  public WorkspaceMemberService(
      AppUserRepository appUserRepository,
      WorkspaceMemberRepository workspaceMemberRepository,
      AuthorizationDenialAuditService authorizationDenialAuditService,
      VersionPreconditionService versionPreconditionService) {
    this.appUserRepository = appUserRepository;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.authorizationDenialAuditService = authorizationDenialAuditService;
    this.versionPreconditionService = versionPreconditionService;
  }

  @Transactional(readOnly = true)
  public WorkspaceMemberSummaryResponse getMember(
      UUID targetMemberId, AuthenticatedUserPrincipal actor) {
    UUID actingMemberId = requireActingMember(actor);
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
    UUID actingMemberId = requireActingMember(actor);
    requireSelf(targetMemberId, actingMemberId, actor.userId());

    // Locks the target plus every other currently-ACTIVE member of the same workspace in one
    // statement - see the repository query's own comment for why this must happen atomically
    // rather than as a separate count query.
    List<WorkspaceMember> lockedMembers =
        workspaceMemberRepository.lockTargetAndActiveMembersInWorkspace(
            targetMemberId, actor.workspaceId());
    WorkspaceMember target = extractTargetOrThrow(lockedMembers, targetMemberId, actor.userId());
    versionPreconditionService.requireCurrent(
        expectedVersion, target.getVersion(), "workspace member");

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

  // Mirrors AccessControlService.requireActingMember (US-03-03) in spirit - resolving "who is
  // making this request" as a workspace_member id. It predates that class and stays separate until
  // the follow-up in the class Javadoc routes this service through it. This is a narrow identity
  // lookup, not a reimplementation of access-level computation, so duplicating it here is not the
  // per-service authorization reimplementation AccessControlService exists to prevent. Not routed
  // through AuthorizationDenialAuditService below: that service's own Javadoc scopes it to a
  // caller-supplied id that doesn't resolve to a row the caller is entitled to - this is the
  // caller's own identity failing to resolve at all, a different situation (mirrored by
  // AccessControlService.requireActingMember, which doesn't route through it either).
  private UUID requireActingMember(AuthenticatedUserPrincipal actor) {
    return appUserRepository
        .findWorkspaceMemberId(actor.userId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
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
        member.isDependent(),
        member.getStatus(),
        member.getMemberSince(),
        member.getMemberUntil(),
        VersionPreconditionService.persistedVersion(member.getVersion(), "workspace member"));
  }
}
