package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AppUser;
import com.trackmywealth.backend.entity.FinancialInstitution;
import com.trackmywealth.backend.entity.SharingGrant;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AccountOwnershipRepository;
import com.trackmywealth.backend.repository.AppUserRepository;
import com.trackmywealth.backend.repository.SharingGrantRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-03-03/FR-TEN-002/008/009: the one place every workspace-scoped read/write consults {@code
 * account_ownership} + {@code sharing_grant} to decide whether the acting member may proceed (the
 * story's own "Authorization/privacy" line) - not a per-controller reimplementation. Deny is always
 * surfaced as {@link HttpStatus#NOT_FOUND}, identical to a genuinely nonexistent/cross-workspace id
 * (FR-TEN-006/US-28-02's existing convention), so a denied caller can never distinguish "doesn't
 * exist" from "exists but you can't see it".
 *
 * <p>An actor's effective access level to a resource ({@link #effectiveAccessLevel}, the single
 * core computation every {@code require*Access}/{@code *AccessLevel} method below funnels through),
 * strongest source wins:
 *
 * <ol>
 *   <li>The sole active member of a workspace has implicit {@code FULL} access to everything in it.
 *       With only one active member, no {@code sharing_grant} could possibly exist yet to grant
 *       them access to their own workspace's data - this is what makes the workspace usable
 *       immediately after {@code SetupService} bootstraps it, before any grant has ever been
 *       created. Deliberately structural, not role-based: {@link AppUser#getRole()} confers
 *       administration rights only, never financial-data access (see its own Javadoc) - a {@code
 *       SYSTEM_ADMINISTRATOR} in a multi-member workspace gets no special treatment here.
 *   <li>An account's currently-effective owner ({@code account_ownership}, {@link
 *       AccountOwnershipRepository}) has implicit {@code FULL} access to that one account without
 *       needing a {@code sharing_grant} row of their own - a grant is only needed to extend access
 *       to a non-owner. {@code AccountService.createAccount} gives the creator this immediately, so
 *       an account is never left ownerless in the first place (see its own Javadoc).
 *   <li>Otherwise, the highest {@code access_level} among every non-revoked {@code sharing_grant}
 *       applicable to the resource: an {@code ACCOUNT}-scope grant for that account, an {@code
 *       INSTITUTION}-scope grant for the account's institution, or a {@code WORKSPACE}-scope grant
 *       - broader scopes cascade down, matching the story's own "one account, one institution, or
 *       the whole workspace" description. No applicable grant means {@code NO_ACCESS} (FR-TEN-002:
 *       deny by default).
 * </ol>
 */
@Service
public class AccessControlService {

  private final AppUserRepository appUserRepository;
  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final AccountOwnershipRepository accountOwnershipRepository;
  private final SharingGrantRepository sharingGrantRepository;

  public AccessControlService(
      AppUserRepository appUserRepository,
      WorkspaceMemberRepository workspaceMemberRepository,
      AccountOwnershipRepository accountOwnershipRepository,
      SharingGrantRepository sharingGrantRepository) {
    this.appUserRepository = appUserRepository;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.accountOwnershipRepository = accountOwnershipRepository;
    this.sharingGrantRepository = sharingGrantRepository;
  }

  @Transactional(readOnly = true)
  public void requireAccountAccess(
      AuthenticatedUserPrincipal actor, Account account, String requiredLevel) {
    requireAccountAccess(requireActingMember(actor), account, requiredLevel);
  }

  // memberId overload: for a caller that has already resolved the acting member's id for its own
  // purposes (e.g. SharingGrantService.grant() needs it for grantedByMemberId regardless), so it
  // isn't forced to pay for requireActingMember's AppUserRepository lookup a second time just to
  // also gate the same request.
  @Transactional(readOnly = true)
  public void requireAccountAccess(UUID memberId, Account account, String requiredLevel) {
    denyUnless(atLeast(accountAccessLevel(memberId, account), requiredLevel), "Account not found.");
  }

  @Transactional(readOnly = true)
  public void requireInstitutionAccess(
      AuthenticatedUserPrincipal actor, FinancialInstitution institution, String requiredLevel) {
    requireInstitutionAccess(requireActingMember(actor), institution, requiredLevel);
  }

  @Transactional(readOnly = true)
  public void requireInstitutionAccess(
      UUID memberId, FinancialInstitution institution, String requiredLevel) {
    denyUnless(
        atLeast(institutionAccessLevel(memberId, institution), requiredLevel),
        "Financial institution not found.");
  }

  @Transactional(readOnly = true)
  public void requireWorkspaceAccess(
      AuthenticatedUserPrincipal actor, UUID workspaceId, String requiredLevel) {
    requireWorkspaceAccess(requireActingMember(actor), workspaceId, requiredLevel);
  }

  @Transactional(readOnly = true)
  public void requireWorkspaceAccess(UUID memberId, UUID workspaceId, String requiredLevel) {
    denyUnless(
        atLeast(workspaceAccessLevel(memberId, workspaceId), requiredLevel),
        "Workspace not found.");
  }

  // Public, level-returning counterparts of the require* methods above - for a future caller that
  // needs to know what an actor can do (e.g. to render available actions in a UI) rather than gate
  // one specific action, without re-deriving the sole-member/ownership/grants logic itself. Each
  // has the same actor/memberId overload pair as its require* counterpart, for the same reason.
  @Transactional(readOnly = true)
  public String accountAccessLevel(AuthenticatedUserPrincipal actor, Account account) {
    return accountAccessLevel(requireActingMember(actor), account);
  }

  @Transactional(readOnly = true)
  public String accountAccessLevel(UUID memberId, Account account) {
    return effectiveAccessLevel(
        memberId,
        account.getWorkspace().getId(),
        account.getId(),
        account.getFinancialInstitution().getId(),
        true);
  }

  @Transactional(readOnly = true)
  public String institutionAccessLevel(
      AuthenticatedUserPrincipal actor, FinancialInstitution institution) {
    return institutionAccessLevel(requireActingMember(actor), institution);
  }

  @Transactional(readOnly = true)
  public String institutionAccessLevel(UUID memberId, FinancialInstitution institution) {
    return effectiveAccessLevel(
        memberId, institution.getWorkspace().getId(), null, institution.getId(), false);
  }

  @Transactional(readOnly = true)
  public String workspaceAccessLevel(AuthenticatedUserPrincipal actor, UUID workspaceId) {
    return workspaceAccessLevel(requireActingMember(actor), workspaceId);
  }

  @Transactional(readOnly = true)
  public String workspaceAccessLevel(UUID memberId, UUID workspaceId) {
    return effectiveAccessLevel(memberId, workspaceId, null, null, false);
  }

  // The single core computation every method above funnels through (first extracted after
  // requireAccountAccess/requireInstitutionAccess/requireWorkspaceAccess had each independently
  // reimplemented this same sole-member -> ownership -> grants sequence, differing only in which
  // repository supplied the ownership short-circuit and which id fed findApplicableGrants).
  // checkOwnership is false for institution/workspace scope, which have no ownership concept of
  // their own; accountId/institutionId are simply left null for whichever scope doesn't apply -
  // findApplicableGrants already treats a null id as "this clause never matches" (see its own
  // Javadoc), so no separate query shape is needed per scope.
  private String effectiveAccessLevel(
      UUID memberId, UUID workspaceId, UUID accountId, UUID institutionId, boolean checkOwnership) {
    boolean implicitFull =
        isSoleActiveMember(workspaceId)
            || (checkOwnership
                && accountOwnershipRepository
                    .existsByAccountIdAndWorkspaceMemberIdAndEffectiveToIsNull(
                        accountId, memberId));
    return implicitFull
        ? AccessLevelValues.FULL
        : maxGrantedLevel(
            sharingGrantRepository.findApplicableGrants(memberId, accountId, institutionId));
  }

  // Shared by every controller/service that needs "who is making this request" as a
  // workspace_member id, not just the app_user id AuthenticatedUserPrincipal itself carries - see
  // its own Javadoc for why workspaceId (and by extension the linked member) is null for a
  // SYSTEM_ADMINISTRATOR with no workspace_member, a normal state that simply has no
  // workspace-scoped resource to ever legitimately request.
  @Transactional(readOnly = true)
  public UUID requireActingMember(AuthenticatedUserPrincipal actor) {
    UUID memberId =
        actor.workspaceId() == null
            ? null
            : appUserRepository
                .findById(actor.userId())
                .map(AppUser::getWorkspaceMember)
                .map(WorkspaceMember::getId)
                .orElse(null);
    if (memberId == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found.");
    }
    return memberId;
  }

  // The caller's own membership is already known-ACTIVE by this point: JwtAuthenticationFilter
  // refuses to authenticate a request whose linked workspace_member exists but isn't ACTIVE (see
  // AccountService's own Javadoc for the same reliance) - so "exactly one ACTIVE, non-dependent
  // member in this workspace" already implies that one member is the caller, with no separate
  // equality check (and no memberId parameter) needed here. Excludes dependent members
  // (WorkspaceMember's own Javadoc: "a person represented financially; may have no login") - a
  // dependent can never authenticate, so counting one toward this rule would strip the one real
  // login user of their implicit FULL access the moment a dependent is added, even though no
  // second person can actually use the workspace.
  private boolean isSoleActiveMember(UUID workspaceId) {
    return workspaceMemberRepository.countByWorkspaceIdAndStatusAndDependentFalse(
            workspaceId, "ACTIVE")
        == 1;
  }

  private String maxGrantedLevel(List<SharingGrant> grants) {
    return grants.stream()
        .map(SharingGrant::getAccessLevel)
        .map(AccessLevelValues.Rank::valueOf)
        .max(Comparator.naturalOrder())
        .map(Enum::name)
        .orElse(AccessLevelValues.NO_ACCESS);
  }

  private static boolean atLeast(String actualLevel, String requiredLevel) {
    return AccessLevelValues.Rank.valueOf(actualLevel)
            .compareTo(AccessLevelValues.Rank.valueOf(requiredLevel))
        >= 0;
  }

  private static void denyUnless(boolean allowed, String notFoundMessage) {
    if (!allowed) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFoundMessage);
    }
  }
}
