package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.WorkspaceMemberSummaryResponse;
import com.trackmywealth.backend.entity.AppUser;
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
 * caller's own {@code workspace_member} row. US-03-03 (not yet merged at the time of writing) will
 * introduce {@code AccessControlService}, the one sanctioned place to compute whether a caller has
 * {@code FULL} access to someone else's membership - that check belongs there, not reimplemented
 * here, once it exists. Extending this method to let a {@code FULL}-access member deactivate
 * another is then a small follow-up: relax {@link #requireSelf}, nothing else about the guard below
 * changes.
 */
@Service
public class WorkspaceMemberService {

  private static final String ACTIVE = "ACTIVE";
  private static final String INACTIVE = "INACTIVE";

  // FR-HHL-015: a workspace must always retain at least this many active members - deactivating
  // one is only rejected when it would drop below this. Named for the same reason
  // AdminUserService.MINIMUM_ACTIVE_ADMINISTRATORS is (PMD's AvoidLiteralsInIfCondition), not just
  // style: it's the one number this entire guard exists to enforce.
  private static final long MINIMUM_ACTIVE_MEMBERS = 1;

  private final AppUserRepository appUserRepository;
  private final WorkspaceMemberRepository workspaceMemberRepository;

  public WorkspaceMemberService(
      AppUserRepository appUserRepository, WorkspaceMemberRepository workspaceMemberRepository) {
    this.appUserRepository = appUserRepository;
    this.workspaceMemberRepository = workspaceMemberRepository;
  }

  @Transactional
  public WorkspaceMemberSummaryResponse deactivateMember(
      UUID targetMemberId, AuthenticatedUserPrincipal actor) {
    UUID actingMemberId = requireActingMember(actor);
    requireSelf(targetMemberId, actingMemberId);

    // Locks the target plus every other currently-ACTIVE member of the same workspace in one
    // statement - see the repository query's own comment for why this must happen atomically
    // rather than as a separate count query.
    List<WorkspaceMember> lockedMembers =
        workspaceMemberRepository.lockTargetAndActiveMembersInWorkspace(targetMemberId);
    WorkspaceMember target = extractTargetOrThrow(lockedMembers, targetMemberId);

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
    target = workspaceMemberRepository.save(target);
    return toSummary(target);
  }

  // Mirrors AccessControlService.requireActingMember (US-03-03) in spirit - resolving "who is
  // making this request" as a workspace_member id - but is deliberately not a call to that class:
  // it does not exist on this branch yet (see class Javadoc). This is a narrow identity lookup,
  // not a reimplementation of access-level computation, so duplicating it here is not the
  // per-service authorization reimplementation AccessControlService exists to prevent.
  private UUID requireActingMember(AuthenticatedUserPrincipal actor) {
    UUID memberId =
        appUserRepository
            .findById(actor.userId())
            .map(AppUser::getWorkspaceMember)
            .map(WorkspaceMember::getId)
            .orElse(null);
    if (memberId == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found.");
    }
    return memberId;
  }

  private void requireSelf(UUID targetMemberId, UUID actingMemberId) {
    if (!actingMemberId.equals(targetMemberId)) {
      // Same 404-for-denied convention AccessControlService uses (FR-TEN-006/US-28-02): a caller
      // who cannot act on this member must not be able to tell "not yours" from "does not exist".
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace member not found.");
    }
  }

  private WorkspaceMember extractTargetOrThrow(List<WorkspaceMember> lockedRows, UUID targetId) {
    return lockedRows.stream()
        .filter(member -> member.getId().equals(targetId))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found."));
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
        member.getMemberUntil());
  }
}
